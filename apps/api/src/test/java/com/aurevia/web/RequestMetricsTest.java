package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * WEB-06 tests for the in-memory metric registry and its Prometheus rendering.
 *
 * <p>The registry is the single source of the exposed series; these tests pin the
 * label names, status-class mapping, latency conversion and escaping so the
 * scrape cannot leak or corrupt a label value.</p>
 */
class RequestMetricsTest {

    @Test
    void recordsCountLatencyAndStatusClassPerSeries() {
        RequestMetrics metrics = new RequestMetrics();
        metrics.record("GET", "/api/v1/patients/{id}", 200, 1_500_000_000L);
        metrics.record("GET", "/api/v1/patients/{id}", 503, 500_000_000L);
        metrics.record("POST", "/api/v1/orders", 201, 1_000_000_000L);

        String scrape = metrics.scrape();
        assertTrue(scrape.contains(
                "orevia_http_requests_total{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"2xx\"} 1\n"),
                scrape);
        assertTrue(scrape.contains(
                "orevia_http_requests_total{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"5xx\"} 1\n"),
                scrape);
        assertTrue(scrape.contains(
                "orevia_http_requests_total{method=\"POST\",route=\"/api/v1/orders\",status=\"2xx\"} 1\n"),
                scrape);
        assertTrue(scrape.contains(
                "orevia_http_request_seconds_sum{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"2xx\"} 1.5\n"),
                scrape);
        assertTrue(scrape.contains(
                "orevia_http_request_seconds_sum{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"5xx\"} 0.5\n"),
                scrape);
        // The histogram's sum and count must agree with the existing counter families.
        assertTrue(scrape.contains(
                "orevia_http_request_duration_seconds_sum{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"2xx\"} 1.5\n"),
                scrape);
        assertTrue(scrape.contains(
                "orevia_http_request_duration_seconds_count{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"2xx\"} 1\n"),
                scrape);
        assertTrue(scrape.contains(
                "orevia_http_request_duration_seconds_bucket{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"2xx\",le=\"+Inf\"} 1\n"),
                scrape);
    }

    @Test
    void recordsCumulativeHistogramBucketsAtBoundaries() {
        RequestMetrics metrics = new RequestMetrics();
        metrics.record("GET", "/api/v1/patients/{id}", 200, 1_000_000L);      // below first bound (0.005 s)
        metrics.record("GET", "/api/v1/patients/{id}", 200, 500_000_000L);    // exactly the 0.5 s bound
        metrics.record("GET", "/api/v1/patients/{id}", 200, 900_000_000L);    // exactly the 0.9 s bound
        metrics.record("GET", "/api/v1/patients/{id}", 200, 11_000_000_000L); // above last bound (10 s)

        String scrape = metrics.scrape();
        String series = "method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"2xx\"";
        // Cumulative: a sample in a bucket is in every higher bucket, and <= includes equality.
        assertTrue(scrape.contains("orevia_http_request_duration_seconds_bucket{" + series + ",le=\"0.005\"} 1\n"), scrape);
        assertTrue(scrape.contains("orevia_http_request_duration_seconds_bucket{" + series + ",le=\"0.5\"} 2\n"), scrape);
        assertTrue(scrape.contains("orevia_http_request_duration_seconds_bucket{" + series + ",le=\"0.9\"} 3\n"), scrape);
        assertTrue(scrape.contains("orevia_http_request_duration_seconds_bucket{" + series + ",le=\"10.0\"} 3\n"), scrape);
        assertTrue(scrape.contains("orevia_http_request_duration_seconds_bucket{" + series + ",le=\"+Inf\"} 4\n"), scrape);
        assertTrue(scrape.contains("orevia_http_request_duration_seconds_count{" + series + "} 4\n"), scrape);
        // le labels are emitted in ascending order with +Inf last.
        int first = scrape.indexOf("le=\"0.005\"");
        int half = scrape.indexOf("le=\"0.5\"");
        int nine = scrape.indexOf("le=\"0.9\"");
        int ten = scrape.indexOf("le=\"10.0\"");
        int infinity = scrape.indexOf("le=\"+Inf\"");
        assertTrue(first >= 0 && half > first && nine > half && ten > nine && infinity > ten, scrape);
    }

    @Test
    void escapesBackslashQuoteAndNewlineInLabelValues() {
        RequestMetrics metrics = new RequestMetrics();
        metrics.record("GET", "/route/with\"quote\\slash\nnewline", 200, 1L);

        String scrape = metrics.scrape();
        assertTrue(scrape.contains("route=\"/route/with\\\"quote\\\\slash\\nnewline\""), scrape);
        assertFalse(scrape.contains("\nnewline"), scrape);
        // The same escaping applies to the bucket label built from the same key.
        assertTrue(scrape.contains("orevia_http_request_duration_seconds_bucket{method=\"GET\","
                + "route=\"/route/with\\\"quote\\\\slash\\nnewline\",status=\"2xx\",le=\"0.005\"} 1\n"), scrape);
    }

    @Test
    void emptyRegistryEmitsMetadataWithoutSamples() {
        String scrape = new RequestMetrics().scrape();

        assertTrue(scrape.startsWith("# HELP orevia_http_requests_total"), scrape);
        assertTrue(scrape.contains("# TYPE orevia_http_requests_total counter"), scrape);
        assertTrue(scrape.contains("# TYPE orevia_http_request_seconds_sum counter"), scrape);
        assertTrue(scrape.contains("# HELP orevia_http_request_duration_seconds"), scrape);
        assertTrue(scrape.contains("# TYPE orevia_http_request_duration_seconds histogram"), scrape);
        assertFalse(scrape.contains("method="), scrape);
        assertFalse(scrape.contains("le="), scrape);
    }
}
