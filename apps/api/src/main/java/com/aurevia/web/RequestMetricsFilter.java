package com.aurevia.web;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Measures every completed request into the shared {@link com.aurevia.web.RequestMetrics}
 * registry (WEB-06).
 *
 * <p>The route label is the matched handler pattern exposed by Spring MVC; the
 * raw URI is never read, so identifiers in a path cannot reach a metric label.
 * Measurement happens in a {@code finally} block so failed requests are counted
 * with their error status class. Requests with no matched handler use a constant
 * label.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public final class RequestMetricsFilter extends OncePerRequestFilter {

    /** Route label used when Spring matched no handler pattern. */
    private static final String UNMATCHED_ROUTE = "UNMATCHED";

    private final RequestMetrics metrics;

    /**
     * Create the filter with the shared registry.
     *
     * @param metrics registry that receives one observation per request
     */
    public RequestMetricsFilter(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            metrics.record(request.getMethod(), routeOf(request), response.getStatus(),
                    System.nanoTime() - start);
        }
    }

    /**
     * Resolve the matched handler pattern, never the raw request path.
     *
     * @param request current HTTP request
     * @return the matched pattern, or a constant label when nothing matched
     */
    private static String routeOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String route ? route : UNMATCHED_ROUTE;
    }
}
