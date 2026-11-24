package com.aurevia.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Enforces the absolute staff-web session ceiling independently of idle expiry
 * (WEB-03). The servlet container's idle timeout only covers inactivity; a
 * session that keeps being touched would otherwise live forever. This filter
 * invalidates the server-side session once its creation time is older than the
 * configured maximum and answers 401 so the client shows its session-expired
 * state.
 *
 * <p>The {@link java.time.Clock} is injected so expiry is deterministic in
 * tests. The filter is deliberately created inside the staff web filter chain
 * rather than as a servlet {@code Filter} bean, so it never runs for bearer
 * requests or under the {@code synthetic-auth} profile.</p>
 */
final class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final Duration maximumLifetime;

    /**
     * Create the absolute lifetime guard.
     *
     * @param clock            injected time source
     * @param maximumLifetime maximum allowed session age
     */
    AbsoluteSessionTimeoutFilter(Clock clock, Duration maximumLifetime) {
        this.clock = clock;
        this.maximumLifetime = maximumLifetime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null
                && clock.millis() - session.getCreationTime() > maximumLifetime.toMillis()) {
            session.invalidate();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
