package com.aurevia.smartclinic.web;

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
                "smartclinic_http_requests_total{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"2xx\"} 1\n"),
                scrape);
        assertTrue(scrape.contains(
                "smartclinic_http_requests_total{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"5xx\"} 1\n"),
                scrape);
        assertTrue(scrape.contains(
                "smartclinic_http_requests_total{method=\"POST\",route=\"/api/v1/orders\",status=\"2xx\"} 1\n"),
                scrape);
        assertTrue(scrape.contains(
                "smartclinic_http_request_seconds_sum{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"2xx\"} 1.5\n"),
                scrape);
        assertTrue(scrape.contains(
                "smartclinic_http_request_seconds_sum{method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"5xx\"} 0.5\n"),
                scrape);
    }

    @Test
    void escapesBackslashQuoteAndNewlineInLabelValues() {
        RequestMetrics metrics = new RequestMetrics();
        metrics.record("GET", "/route/with\"quote\\slash\nnewline", 200, 1L);

        String scrape = metrics.scrape();
        assertTrue(scrape.contains("route=\"/route/with\\\"quote\\\\slash\\nnewline\""), scrape);
        assertFalse(scrape.contains("\nnewline"), scrape);
    }

    @Test
    void emptyRegistryEmitsMetadataWithoutSamples() {
        String scrape = new RequestMetrics().scrape();

        assertTrue(scrape.startsWith("# HELP smartclinic_http_requests_total"), scrape);
        assertTrue(scrape.contains("# TYPE smartclinic_http_requests_total counter"), scrape);
        assertTrue(scrape.contains("# TYPE smartclinic_http_request_seconds_sum counter"), scrape);
        assertFalse(scrape.contains("method="), scrape);
    }
}
