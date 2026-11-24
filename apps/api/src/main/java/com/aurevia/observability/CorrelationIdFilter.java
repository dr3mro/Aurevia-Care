package com.aurevia.observability;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Assigns a correlation id to every request and writes one structured JSON log line (WEB-06).
 *
 * <p>The id is taken from {@code X-Correlation-Id} only when it is a valid UUID;
 * any missing, blank or malformed value is replaced with a freshly generated UUID.
 * The id is placed in the SLF4J MDC for the duration of the request, echoed in the
 * response header, and the MDC is always cleared afterwards, including when the
 * downstream chain throws. The completion log is produced solely by
 * {@link com.aurevia.observability.RequestLogFormatter}, which has a
 * fixed field list and cannot include a query string, body, header or user value.</p>
 *
 * <p>The route is the matched handler pattern exposed by Spring MVC rather than the
 * raw request URI, so path parameters (which may carry patient data) never reach the
 * log. Requests with no matched handler use a constant route label.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Name of the request/response correlation header. */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    /** SLF4J MDC key under which the correlation id is published. */
    public static final String MDC_KEY = "correlationId";
    /** Route label used when no handler pattern was matched. */
    public static final String UNMATCHED_ROUTE = "UNMATCHED";

    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private final Clock clock;

    /**
     * Create the filter with the injected application clock.
     *
     * @param clock time source used to compute request latency
     */
    @Autowired
    public CorrelationIdFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);
        long start = clock.millis();
        try {
            chain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            String route = resolveRoute(request);
            long latencyMs = clock.millis() - start;
            LOG.info(RequestLogFormatter.toJson(correlationId, request.getMethod(), route, status,
                    latencyMs));
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Reuse a valid UUID header, otherwise generate a fresh correlation id.
     *
     * @param header value of the correlation header, possibly absent
     * @return an opaque correlation id
     */
    private static String resolveCorrelationId(String header) {
        if (header != null && isUuid(header)) {
            return header;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Test whether a header value is a valid UUID.
     *
     * @param value candidate header value
     * @return {@code true} when the value parses as a UUID
     */
    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Resolve the matched handler pattern, never the raw request path.
     *
     * @param request current HTTP request
     * @return the matched pattern, or a constant label when nothing matched
     */
    private static String resolveRoute(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String route ? route : UNMATCHED_ROUTE;
    }
}
