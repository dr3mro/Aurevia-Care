package com.aurevia.smartclinic.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

/**
 * Instance-local registry of HTTP request counts, latency sums and latency
 * histograms (WEB-06, OPS-03).
 *
 * <p>Each series is keyed by HTTP method, the matched Spring route pattern and
 * the status class, never the raw request path, so a path parameter carrying a
 * patient id can never become a label. The registry is append-only and safe for
 * concurrent requests; it intentionally keeps no per-request detail. Latency is
 * accumulated in nanoseconds and rendered as seconds to match the Prometheus
 * convention.</p>
 *
 * <p>The histogram uses a fixed, bounded set of upper bounds plus the mandatory
 * {@code +Inf} bucket. It is cumulative: an observation increments every bucket
 * whose bound is greater than or equal to its duration. The {@code +Inf} bucket
 * therefore always increments. Count, sum and buckets are read from one holder,
 * so the families describe the same series rather than three independently keyed maps.</p>
 *
 * <p>Count, sum and every bucket live in one holder that is published to the
 * registry inside a single atomic {@code compute}, so a scrape never sees a
 * holder without its sum and buckets. The adders are lock-free, so individual
 * samples in one scrape may still straddle an in-flight observation.</p>
 */
@Component
public final class RequestMetrics {

    private static final String REQUESTS_METRIC = "smartclinic_http_requests_total";
    private static final String SECONDS_METRIC = "smartclinic_http_request_seconds_sum";
    private static final String DURATION_METRIC = "smartclinic_http_request_duration_seconds";
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /**
     * Bucket upper bounds in seconds. The 0.5 s and 0.9 s entries align with the
     * OPS-03 p95 targets for routine reads and commands so a percentile query can
     * resolve an alert threshold at the target instead of interpolating a wide gap.
     */
    private static final String[] BUCKET_LABELS = {
            "0.005", "0.01", "0.025", "0.05", "0.1", "0.25", "0.5", "0.75",
            "0.9", "1", "2.5", "5", "10", "+Inf"
    };

    /** Upper bounds matching {@code BUCKET_LABELS}, in nanoseconds; the last is unbounded. */
    private static final long[] BUCKET_UPPER_BOUNDS_NANOS = {
            5_000_000L, 10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L,
            250_000_000L, 500_000_000L, 750_000_000L, 900_000_000L, 1_000_000_000L,
            2_500_000_000L, 5_000_000_000L, 10_000_000_000L, Long.MAX_VALUE
    };

    private final ConcurrentHashMap<String, Series> series = new ConcurrentHashMap<>();

    /**
     * Count, latency sum and cumulative buckets for one method/route/status series.
     * All adders are created up front so a published holder is always complete.
     */
    private static final class Series {
        private final LongAdder requests = new LongAdder();
        private final LongAdder latencyNanos = new LongAdder();
        private final LongAdder[] buckets = new LongAdder[BUCKET_LABELS.length];

        private Series() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }
    }

    /**
     * Add one observation to the matching series.
     *
     * <p>The whole update happens inside one atomic map computation: the holder
     * becomes visible to scrapes only after its count, sum and buckets have all
     * been incremented, so no scrape can observe a half-written observation.</p>
     *
     * @param method       HTTP method from the servlet request
     * @param route        matched handler pattern, never the raw request path
     * @param status       HTTP status code of the completed response
     * @param elapsedNanos elapsed request time in nanoseconds
     */
    public void record(String method, String route, int status, long elapsedNanos) {
        String key = method + '|' + route + '|' + (status / 100) + "xx";
        series.compute(key, (ignored, existing) -> {
            Series current = existing == null ? new Series() : existing;
            current.requests.increment();
            current.latencyNanos.add(elapsedNanos);
            for (int i = 0; i < BUCKET_UPPER_BOUNDS_NANOS.length; i++) {
                if (elapsedNanos <= BUCKET_UPPER_BOUNDS_NANOS[i]) {
                    current.buckets[i].increment();
                }
            }
            return current;
        });
    }

    /**
     * Render every series in the Prometheus text exposition format.
     *
     * <p>Series are ordered by key so a scrape is deterministic. Label values
     * are escaped before they are written. The two legacy counter families keep
     * their original names so existing dashboards remain valid; the histogram
     * family adds bucket, sum and count samples for percentile queries.</p>
     *
     * @return the complete scrape body, including help and type metadata
     */
    public String scrape() {
        StringBuilder text = new StringBuilder();
        text.append("# HELP ").append(REQUESTS_METRIC)
                .append(" Total HTTP requests by method, route and status class.\n");
        text.append("# TYPE ").append(REQUESTS_METRIC).append(" counter\n");
        appendSeries(text, REQUESTS_METRIC, series, false);
        text.append("# HELP ").append(SECONDS_METRIC)
                .append(" Sum of request latency in seconds by method, route and status class.\n");
        text.append("# TYPE ").append(SECONDS_METRIC).append(" counter\n");
        appendSeries(text, SECONDS_METRIC, series, true);
        appendHistogram(text, series);
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
            ConcurrentHashMap<String, Series> values, boolean seconds) {
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Series current = entry.getValue();
            long total = seconds ? current.latencyNanos.sum() : current.requests.sum();
            String value = seconds ? Double.toString(total / NANOS_PER_SECOND) : Long.toString(total);
            String[] keyParts = entry.getKey().split("\\|", -1);
            text.append(metric).append("{method=").append(label(keyParts[0]))
                    .append(",route=").append(label(keyParts[1]))
                    .append(",status=").append(label(keyParts[2])).append("} ")
                    .append(value).append('\n');
        });
    }

    /**
     * Append the duration histogram family, with cumulative buckets rendered in
     * ascending bound order for each observed series. This is static so no lambda
     * in this method captures the registry instance; SpotBugs would otherwise treat
     * the injected registry stored by the metrics filter as an exposed mutable field.
     *
     * @param text   output buffer
     * @param values series values keyed by {@code method|route|status}
     */
    private static void appendHistogram(StringBuilder text, ConcurrentHashMap<String, Series> values) {
        text.append("# HELP ").append(DURATION_METRIC)
                .append(" Server-side request duration in seconds by method, route and status class.\n");
        text.append("# TYPE ").append(DURATION_METRIC).append(" histogram\n");
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Series current = entry.getValue();
            String[] keyParts = entry.getKey().split("\\|", -1);
            String labels = "method=" + label(keyParts[0]) + ",route=" + label(keyParts[1])
                    + ",status=" + label(keyParts[2]);
            for (int i = 0; i < BUCKET_LABELS.length; i++) {
                text.append(DURATION_METRIC).append("_bucket{").append(labels)
                        .append(",le=").append(label(BUCKET_LABELS[i])).append("} ")
                        .append(current.buckets[i].sum()).append('\n');
            }
            text.append(DURATION_METRIC).append("_sum{").append(labels).append("} ")
                    .append(current.latencyNanos.sum() / NANOS_PER_SECOND).append('\n');
            text.append(DURATION_METRIC).append("_count{").append(labels).append("} ")
                    .append(current.requests.sum()).append('\n');
        });
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
