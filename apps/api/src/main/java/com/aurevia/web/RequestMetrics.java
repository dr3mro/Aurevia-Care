package com.aurevia.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

/**
 * Instance-local registry of HTTP request counts, latency sums and latency
 * histograms (WEB-06).
 *
 * <p>Each series is keyed by HTTP method, the matched Spring route pattern and
 * the status class, never the raw request path, so a path parameter carrying a
 * patient id can never become a label. The registry is append-only and safe for
 * concurrent requests; it intentionally keeps no per-request detail. Latency is
 * accumulated in nanoseconds and rendered as seconds to match the Prometheus
 * convention. Bounded cumulative buckets allow p95 to be computed from the
 * scrape with {@code histogram_quantile} (OPS-03) without changing either
 * existing counter family.</p>
 */
@Component
public final class RequestMetrics {

    private static final String REQUESTS_METRIC = "orevia_http_requests_total";
    private static final String SECONDS_METRIC = "orevia_http_request_seconds_sum";
    private static final String DURATION_METRIC = "orevia_http_request_duration_seconds";
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /**
     * Fixed upper bounds in nanoseconds. Buckets straddle the OPS-03 targets of
     * 500 ms for reads and 900 ms for commands so the documented percentile query
     * has a usable bucket on each side of both thresholds.
     */
    private static final long[] BOUNDS_NANOS = {
            5_000_000L, 10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L,
            250_000_000L, 500_000_000L, 750_000_000L, 900_000_000L,
            1_000_000_000L, 2_500_000_000L, 5_000_000_000L, 10_000_000_000L
    };

    /** Seconds-formatted {@code le} labels, derived from {@link com.aurevia.web.RequestMetrics#BOUNDS_NANOS}. */
    private static final String[] LE_LABELS = buildLeLabels();

    private final ConcurrentHashMap<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> latencyNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder[]> histogramBuckets = new ConcurrentHashMap<>();

    /**
     * Add one observation to the matching series.
     *
     * @param method       HTTP method from the servlet request
     * @param route        matched handler pattern, never the raw request path
     * @param status       HTTP status code of the completed response
     * @param elapsedNanos elapsed request time in nanoseconds
     */
    public void record(String method, String route, int status, long elapsedNanos) {
        String key = method + '|' + route + '|' + (status / 100) + "xx";
        requests.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        LongAdder nanos = latencyNanos.computeIfAbsent(key, ignored -> new LongAdder());
        nanos.add(elapsedNanos);
        LongAdder[] buckets = histogramBuckets.computeIfAbsent(key, ignored -> newBuckets());
        // Cumulative: a sample counts in its bound and every higher bound. The
        // comparator is inclusive so an observation exactly on a bound is kept
        // by that bucket, matching Prometheus' "le" semantics.
        for (int i = 0; i < BOUNDS_NANOS.length; i++) {
            if (elapsedNanos <= BOUNDS_NANOS[i]) {
                buckets[i].increment();
            }
        }
    }

    /**
     * Render every series in the Prometheus text exposition format.
     *
     * <p>Series are ordered by key so a scrape is deterministic. Label values
     * are escaped before they are written. The two counter families are emitted
     * first and unchanged; the histogram family is appended after them.</p>
     *
     * @return the complete scrape body, including help and type metadata
     */
    public String scrape() {
        StringBuilder text = new StringBuilder();
        text.append("# HELP ").append(REQUESTS_METRIC)
                .append(" Total HTTP requests by method, route and status class.\n");
        text.append("# TYPE ").append(REQUESTS_METRIC).append(" counter\n");
        appendSeries(text, REQUESTS_METRIC, requests, false);
        text.append("# HELP ").append(SECONDS_METRIC)
                .append(" Sum of request latency in seconds by method, route and status class.\n");
        text.append("# TYPE ").append(SECONDS_METRIC).append(" counter\n");
        appendSeries(text, SECONDS_METRIC, latencyNanos, true);
        text.append("# HELP ").append(DURATION_METRIC)
                .append(" Request duration in seconds by method, route and status class.\n");
        text.append("# TYPE ").append(DURATION_METRIC).append(" histogram\n");
        appendHistogram(text, histogramBuckets, requests, latencyNanos);
        return text.toString();
    }

    /**
     * Append all samples of one metric family in key order.
     *
     * @param text    output buffer
     * @param metric  Prometheus metric name
     * @param values  series values keyed by {@code method|route|status}
     * @param seconds whether values are nanoseconds to be rendered as seconds
     */
    private static void appendSeries(StringBuilder text, String metric,
            ConcurrentHashMap<String, LongAdder> values, boolean seconds) {
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String[] parts = entry.getKey().split("\\|", -1);
            long total = entry.getValue().sum();
            String value = seconds ? Double.toString(total / NANOS_PER_SECOND) : Long.toString(total);
            text.append(metric).append("{method=").append(label(parts[0]))
                    .append(",route=").append(label(parts[1]))
                    .append(",status=").append(label(parts[2])).append("} ")
                    .append(value).append('\n');
        });
    }

    /**
     * Append the bucket, sum and count samples of the latency histogram family.
     *
     * <p>The {@code +Inf} bucket and the {@code _count} sample are derived from
     * the existing request counter, so they can never disagree with it; the
     * {@code _sum} sample is derived from the existing latency sum. Buckets are
     * rendered in ascending bound order with {@code +Inf} last.</p>
     *
     * @param text            output buffer
     * @param buckets         cumulative bucket adders keyed by {@code method|route|status}
     * @param requests        request counters used for {@code +Inf} and {@code _count}
     * @param latencyNanos    latency sums used for {@code _sum}
     */
    private static void appendHistogram(StringBuilder text,
            ConcurrentHashMap<String, LongAdder[]> buckets,
            ConcurrentHashMap<String, LongAdder> requests,
            ConcurrentHashMap<String, LongAdder> latencyNanos) {
        buckets.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String[] parts = entry.getKey().split("\\|", -1);
            String labels = "method=" + label(parts[0]) + ",route=" + label(parts[1])
                    + ",status=" + label(parts[2]);
            LongAdder[] seriesBuckets = entry.getValue();
            long count = requests.get(entry.getKey()).sum();
            for (int i = 0; i < BOUNDS_NANOS.length; i++) {
                text.append(DURATION_METRIC).append("_bucket{").append(labels)
                        .append(",le=").append(label(LE_LABELS[i])).append("} ")
                        .append(seriesBuckets[i].sum()).append('\n');
            }
            text.append(DURATION_METRIC).append("_bucket{").append(labels)
                    .append(",le=").append(label("+Inf")).append("} ")
                    .append(count).append('\n');
            text.append(DURATION_METRIC).append("_sum{").append(labels).append("} ")
                    .append(Double.toString(latencyNanos.get(entry.getKey()).sum() / NANOS_PER_SECOND))
                    .append('\n');
            text.append(DURATION_METRIC).append("_count{").append(labels).append("} ")
                    .append(Long.toString(count)).append('\n');
        });
    }

    /**
     * Format each nanosecond bound as its seconds-valued {@code le} label.
     *
     * @return seconds-formatted label values aligned with {@link com.aurevia.web.RequestMetrics#BOUNDS_NANOS}
     */
    private static String[] buildLeLabels() {
        String[] labels = new String[BOUNDS_NANOS.length];
        for (int i = 0; i < BOUNDS_NANOS.length; i++) {
            labels[i] = Double.toString(BOUNDS_NANOS[i] / NANOS_PER_SECOND);
        }
        return labels;
    }

    /**
     * Create the cumulative bucket adders for a new series.
     *
     * @return one adder per bound, all starting at zero
     */
    private static LongAdder[] newBuckets() {
        LongAdder[] buckets = new LongAdder[BOUNDS_NANOS.length];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
        return buckets;
    }

    /**
     * Quote and escape a Prometheus label value.
     *
     * @param value raw label value
     * @return the value enclosed in double quotes with backslash, quote and
     *         newline escaped
     */
    private static String label(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
