package com.aurevia.smartclinic.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

/**
 * Instance-local registry of HTTP request counts and latency sums (WEB-06).
 *
 * <p>Each series is keyed by HTTP method, the matched Spring route pattern and
 * the status class, never the raw request path, so a path parameter carrying a
 * patient id can never become a label. The registry is append-only and safe for
 * concurrent requests; it intentionally keeps no per-request detail. Latency is
 * accumulated in nanoseconds and rendered as seconds to match the Prometheus
 * counter convention.</p>
 */
@Component
public final class RequestMetrics {

    private static final String REQUESTS_METRIC = "smartclinic_http_requests_total";
    private static final String SECONDS_METRIC = "smartclinic_http_request_seconds_sum";
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final ConcurrentHashMap<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> latencyNanos = new ConcurrentHashMap<>();

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
    }

    /**
     * Render every series in the Prometheus text exposition format.
     *
     * <p>Series are ordered by key so a scrape is deterministic. Label values
     * are escaped before they are written.</p>
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
