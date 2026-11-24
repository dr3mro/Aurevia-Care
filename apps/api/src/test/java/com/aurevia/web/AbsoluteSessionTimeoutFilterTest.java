package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import jakarta.servlet.FilterChain;

/**
 * Deterministic expiry tests for the absolute session lifetime ceiling (WEB-03).
 *
 * <p>The wall clock is injected, and {@link org.springframework.mock.web.MockHttpSession}
 * has no creation-time setter, so stub sessions override {@code getCreationTime()}
 * instead. No real time or real session is involved.</p>
 */
class AbsoluteSessionTimeoutFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration MAXIMUM_LIFETIME = Duration.ofHours(12);

    private final AbsoluteSessionTimeoutFilter filter =
            new AbsoluteSessionTimeoutFilter(CLOCK, MAXIMUM_LIFETIME);

    @Test
    void requestWithoutSessionPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void sessionYoungerThanTheCeilingPassesThrough() throws Exception {
        assertPassesThrough(sessionCreatedAt(NOW.minus(Duration.ofHours(11)).minus(Duration.ofMinutes(59))));
    }

    @Test
    void sessionExactlyAtTheCeilingStillPassesThrough() throws Exception {
        assertPassesThrough(sessionCreatedAt(NOW.minus(MAXIMUM_LIFETIME)));
    }

    @Test
    void sessionOlderThanTheCeilingIsInvalidatedAndRejected() throws Exception {
        MockHttpSession session = sessionCreatedAt(NOW.minus(MAXIMUM_LIFETIME).minusMillis(1));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertTrue(session.isInvalid(), "the stale server-side session must be invalidated");
        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    private void assertPassesThrough(MockHttpSession session) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertFalse(session.isInvalid());
        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    private static MockHttpSession sessionCreatedAt(Instant creationTime) {
        long millis = creationTime.toEpochMilli();
        return new MockHttpSession() {
            @Override
            public long getCreationTime() {
                return millis;
            }
        };
    }
}
