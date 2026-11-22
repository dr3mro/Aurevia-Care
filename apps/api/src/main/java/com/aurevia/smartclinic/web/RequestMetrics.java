package com.aurevia.smartclinic.web;

import java.util.Map;
import java.util.Objects;
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
 * therefore always increments and always equals the request count, so a scrape
 * cannot report a histogram count that disagrees with the counter families.</p>
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

    private final ConcurrentHashMap<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> latencyNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> durationBuckets = new ConcurrentHashMap<>();

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
        latencyNanos.computeIfAbsent(key, ignored -> new LongAdder()).add(elapsedNanos);
        for (int i = 0; i < BUCKET_UPPER_BOUNDS_NANOS.length; i++) {
            if (elapsedNanos <= BUCKET_UPPER_BOUNDS_NANOS[i]) {
                durationBuckets.computeIfAbsent(key + '|' + i, ignored -> new LongAdder()).increment();
            }
        }
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
        appendSeries(text, REQUESTS_METRIC, requests, false);
        text.append("# HELP ").append(SECONDS_METRIC)
                .append(" Sum of request latency in seconds by method, route and status class.\n");
        text.append("# TYPE ").append(SECONDS_METRIC).append(" counter\n");
        appendSeries(text, SECONDS_METRIC, latencyNanos, true);
        appendHistogram(text);
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
     * Append the duration histogram family, with cumulative buckets rendered in
     * ascending bound order for each observed series.
     *
     * @param text output buffer
     */
    private void appendHistogram(StringBuilder text) {
        text.append("# HELP ").append(DURATION_METRIC)
                .append(" Server-side request duration in seconds by method, route and status class.\n");
        text.append("# TYPE ").append(DURATION_METRIC).append(" histogram\n");
        requests.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String key = entry.getKey();
            String[] parts = key.split("\\|", -1);
            String labels = "method=" + label(parts[0]) + ",route=" + label(parts[1])
                    + ",status=" + label(parts[2]);
            for (int i = 0; i < BUCKET_LABELS.length; i++) {
                LongAdder bucket = durationBuckets.get(key + '|' + i);
                long cumulative = bucket == null ? 0L : bucket.sum();
                text.append(DURATION_METRIC).append("_bucket{").append(labels)
                        .append(",le=").append(label(BUCKET_LABELS[i])).append("} ")
                        .append(cumulative).append('\n');
            }
            long sumNanos = Objects.requireNonNull(latencyNanos.get(key),
                    "latency series for a recorded request").sum();
            text.append(DURATION_METRIC).append("_sum{").append(labels).append("} ")
                    .append(sumNanos / NANOS_PER_SECOND).append('\n');
            text.append(DURATION_METRIC).append("_count{").append(labels).append("} ")
                    .append(entry.getValue().sum()).append('\n');
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
