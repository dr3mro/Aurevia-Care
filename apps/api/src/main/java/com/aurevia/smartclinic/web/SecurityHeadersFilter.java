package com.aurevia.smartclinic.web;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Sets browser-defence response headers on every HTTP response (WEB-02).
 *
 * <p>Registered ahead of the rest of the servlet filter chain so that even
 * error and denial responses carry the policy. The content security policy is
 * deliberately restrictive: no inline or remote script sources are allowed and
 * framing is forbidden. Transport security is advertised only when the request
 * actually arrived over TLS; the framework forward-header strategy in the
 * application configuration keeps that accurate behind a TLS-terminating load
 * balancer. Versioned API responses additionally opt out of caching, while the
 * unauthenticated health probes do not.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        if (request.getRequestURI().startsWith(API_PREFIX)) {
            response.setHeader("Cache-Control", "no-store");
        }
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        chain.doFilter(request, response);
    }
}
