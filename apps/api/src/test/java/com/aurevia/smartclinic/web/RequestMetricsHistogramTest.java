package com.aurevia.smartclinic.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * WEB-06 and OPS-03 tests for bounded latency histogram buckets.
 *
 * <p>The histogram must be cumulative, must keep the existing count and sum
 * counters compatible, must carry the same route-template labels as the counters
 * and must expose boundaries at the percentile targets so an operator can alert
 * on p95 latency.</p>
 */
class RequestMetricsHistogramTest {

    private static final String READ_LABELS =
            "method=\"GET\",route=\"/api/v1/patients/{id}\",status=\"2xx\"";

    /** A 100 ms read and a 1.5 s read in the same series make the buckets cumulative. */
    @Test
    void bucketsAreCumulativeAndCountMatchesObservations() {
        RequestMetrics metrics = new RequestMetrics();
        metrics.record("GET", "/api/v1/patients/{id}", 200, 100_000_000L);
        metrics.record("GET", "/api/v1/patients/{id}", 200, 1_500_000_000L);

        String scrape = metrics.scrape();
        // The faster read falls in the 0.1 s bucket; the slower one does not.
        assertTrue(scrape.contains(bucket(READ_LABELS, "0.1", 1)), scrape);
        assertTrue(scrape.contains(bucket(READ_LABELS, "0.5", 1)), scrape);
        assertTrue(scrape.contains(bucket(READ_LABELS, "0.9", 1)), scrape);
        assertTrue(scrape.contains(bucket(READ_LABELS, "1", 1)), scrape);
        // Both reads fall in the 2.5 s and infinite buckets.
        assertTrue(scrape.contains(bucket(READ_LABELS, "2.5", 2)), scrape);
        assertTrue(scrape.contains(bucket(READ_LABELS, "+Inf", 2)), scrape);
        // Sum and count stay compatible with the legacy families.
        assertTrue(scrape.contains("smartclinic_http_request_duration_seconds_sum{"
                + READ_LABELS + "} 1.6\n"), scrape);
        assertTrue(scrape.contains("smartclinic_http_request_duration_seconds_count{"
                + READ_LABELS + "} 2\n"), scrape);
    }

    /** Every observed series renders every bucket, including zero-count buckets. */
    @Test
    void emitsEveryBucketForEachSeriesIncludingZeroCounts() {
        RequestMetrics metrics = new RequestMetrics();
        // 20 s exceeds every finite bound, so only the infinite bucket counts.
        metrics.record("GET", "/api/v1/patients/{id}", 200, 20_000_000_000L);

        String scrape = metrics.scrape();
        assertTrue(scrape.contains(bucket(READ_LABELS, "0.005", 0)), scrape);
        assertTrue(scrape.contains(bucket(READ_LABELS, "10", 0)), scrape);
        assertTrue(scrape.contains(bucket(READ_LABELS, "+Inf", 1)), scrape);
    }

    /** The percentile targets (0.5 s reads, 0.9 s commands) have explicit buckets. */
    @Test
    void exposesBucketsAtThePercentileTargets() {
        RequestMetrics metrics = new RequestMetrics();
        metrics.record("GET", "/api/v1/patients/{id}", 200, 1L);
        metrics.record("POST", "/api/v1/orders", 201, 1L);

        String scrape = metrics.scrape();
        assertTrue(scrape.contains("# TYPE smartclinic_http_request_duration_seconds histogram"), scrape);
        assertTrue(scrape.contains("le=\"0.5\""), scrape);
        assertTrue(scrape.contains("le=\"0.9\""), scrape);
        assertTrue(scrape.contains("route=\"/api/v1/patients/{id}\""), scrape);
        assertTrue(scrape.contains("route=\"/api/v1/orders\""), scrape);
    }

    /** An empty registry publishes histogram metadata but no samples. */
    @Test
    void emptyRegistryEmitsHistogramMetadataWithoutSamples() {
        String scrape = new RequestMetrics().scrape();

        assertTrue(scrape.contains("# HELP smartclinic_http_request_duration_seconds "), scrape);
        assertTrue(scrape.contains("# TYPE smartclinic_http_request_duration_seconds histogram"), scrape);
        assertFalse(scrape.contains("_bucket{"), scrape);
    }

    private static String bucket(String labels, String le, int count) {
        return "smartclinic_http_request_duration_seconds_bucket{" + labels + ",le=\"" + le + "\"} " + count + "\n";
    }
}
