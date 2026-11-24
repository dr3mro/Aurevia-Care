package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.FilterChain;

/**
 * WEB-06 tests for the request-measurement filter.
 *
 * <p>The filter must use the matched Spring route pattern, never the raw URI
 * (which can carry a patient id), must label the status by class, and must still
 * record requests that no handler matched.</p>
 */
class RequestMetricsFilterTest {

    @Test
    void recordsMatchedRouteInsteadOfRawPath() throws Exception {
        RequestMetrics metrics = new RequestMetrics();
        RequestMetricsFilter filter = new RequestMetricsFilter(metrics);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients/patient-999");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/patients/{id}");
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        String scrape = metrics.scrape();
        assertTrue(scrape.contains("route=\"/api/v1/patients/{id}\""), scrape);
        assertFalse(scrape.contains("patient-999"), scrape);
        assertTrue(scrape.contains("status=\"2xx\"} 1\n"), scrape);
        // Timed requests also produce a histogram family whose +Inf bucket matches the counter.
        assertTrue(scrape.contains("aurevia_http_request_duration_seconds_bucket{"), scrape);
        assertTrue(scrape.contains("aurevia_http_request_duration_seconds_bucket{method=\"GET\","
                + "route=\"/api/v1/patients/{id}\",status=\"2xx\",le=\"+Inf\"} 1\n"), scrape);
        assertTrue(scrape.contains("aurevia_http_request_duration_seconds_count{method=\"GET\","
                + "route=\"/api/v1/patients/{id}\",status=\"2xx\"} 1\n"), scrape);
        assertFalse(scrape.contains("patient-999"), scrape);
    }

    @Test
    void recordsUnmatchedRouteWhenNoPatternIsAvailable() throws Exception {
        RequestMetrics metrics = new RequestMetrics();
        RequestMetricsFilter filter = new RequestMetricsFilter(metrics);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/no/such/route");
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertTrue(metrics.scrape().contains("route=\"UNMATCHED\""), metrics.scrape());
    }

    @Test
    void recordsErrorStatusClass() throws Exception {
        RequestMetrics metrics = new RequestMetrics();
        RequestMetricsFilter filter = new RequestMetricsFilter(metrics);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/probe");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/probe");
        FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(503);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertTrue(metrics.scrape().contains("status=\"5xx\"} 1\n"), metrics.scrape());
    }
}
