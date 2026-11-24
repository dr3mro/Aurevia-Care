package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * WEB-06 tests for the address-gated metrics endpoint handler.
 *
 * <p>An allowed remote address receives the Prometheus text body; a remote
 * address outside the allowlist receives a bare 404 so the endpoint is not
 * discoverable from an untrusted network.</p>
 */
class MetricsControllerTest {

    @Test
    void allowedRemoteReceivesPrometheusText() {
        RequestMetrics metrics = new RequestMetrics();
        metrics.record("GET", "/health/live", 200, 1L);
        MetricsController controller = new MetricsController(metrics, new MetricsAccessPolicy(""));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        ResponseEntity<String> response = controller.scrape(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType());
        assertEquals(metrics.scrape(), response.getBody());
        assertTrue(response.getBody().contains("# TYPE orevia_http_request_duration_seconds histogram"));
        assertTrue(response.getBody().contains("orevia_http_request_duration_seconds_bucket{"));
    }

    @Test
    void disallowedRemoteReceivesNotFound() {
        RequestMetrics metrics = new RequestMetrics();
        MetricsController controller = new MetricsController(metrics, new MetricsAccessPolicy(""));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.10");

        ResponseEntity<String> response = controller.scrape(request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody() == null || response.getBody().isEmpty());
    }
}
