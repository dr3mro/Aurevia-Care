package com.aurevia.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Prometheus scrape endpoint for request metrics (WEB-06).
 *
 * <p>Sits next to the health probes outside {@code /api/v1}. It requires no
 * login, but answers only to a source address permitted by
 * {@link com.aurevia.web.MetricsAccessPolicy}; every other source
 * receives a bare 404 so the endpoint is not discoverable from an untrusted
 * network. The body contains only aggregate counters — no request bodies,
 * tokens, tenant or patient fields.</p>
 */
@RestController
public final class MetricsController {

    private final RequestMetrics metrics;
    private final MetricsAccessPolicy access;

    /**
     * Create the endpoint with its registry and source-address policy.
     *
     * @param metrics aggregate request metrics to expose
     * @param access  source-address allowlist for the scrape
     */
    public MetricsController(RequestMetrics metrics, MetricsAccessPolicy access) {
        this.metrics = metrics;
        this.access = access;
    }

    /**
     * Return the request metrics as Prometheus text for an allowed source.
     *
     * @param request current HTTP request, used only for its remote address
     * @return HTTP 200 with the scrape body, or HTTP 404 for a disallowed source
     */
    @GetMapping("/health/metrics")
    public ResponseEntity<String> scrape(HttpServletRequest request) {
        if (!access.allows(request.getRemoteAddr())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(metrics.scrape());
    }
}
