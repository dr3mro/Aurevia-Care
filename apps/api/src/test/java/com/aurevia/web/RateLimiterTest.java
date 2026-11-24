package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the in-memory per-subject limiter (WEB-04). Every clock is
 * synthetic and mutable so window boundaries are deterministic.
 */
class RateLimiterTest {

    private static final Instant START = Instant.parse("2026-11-21T00:00:00Z");

    /** Clock whose instant can be advanced to cross minute and eviction boundaries. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Read the private counter map to prove stale windows are actually removed. */
    @SuppressWarnings("unchecked")
    private static Map<String, ?> windows(RateLimiter limiter) throws Exception {
        Field field = RateLimiter.class.getDeclaredField("windows");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(limiter);
    }

    @Test
    void allowsUpToLimitThenReportsPositiveRetryAfter() {
        MutableClock clock = new MutableClock(START);
        RateLimiter limiter = new RateLimiter(clock, 3);

        assertEquals(0, limiter.retryAfterSeconds("org-a:subject-1"));
        assertEquals(0, limiter.retryAfterSeconds("org-a:subject-1"));
        assertEquals(0, limiter.retryAfterSeconds("org-a:subject-1"));

        long retry = limiter.retryAfterSeconds("org-a:subject-1");
        assertTrue(retry > 0 && retry <= 60, "retry must be inside the current minute: " + retry);
    }

    @Test
    void sameKeyIsAllowedAgainAfterWindowEnds() {
        MutableClock clock = new MutableClock(START);
        RateLimiter limiter = new RateLimiter(clock, 1);

        assertEquals(0, limiter.retryAfterSeconds("org-a:subject-1"));
        assertTrue(limiter.retryAfterSeconds("org-a:subject-1") > 0);

        clock.advance(Duration.ofMinutes(1));
        assertEquals(0, limiter.retryAfterSeconds("org-a:subject-1"));
    }

    @Test
    void twoKeysDoNotShareACounter() {
        RateLimiter limiter = new RateLimiter(new MutableClock(START), 1);

        assertEquals(0, limiter.retryAfterSeconds("org-a:subject-1"));
        assertEquals(0, limiter.retryAfterSeconds("org-a:subject-2"));
        assertTrue(limiter.retryAfterSeconds("org-a:subject-1") > 0);
    }

    @Test
    void organizationHalfOfKeyIsIndependent() {
        RateLimiter limiter = new RateLimiter(new MutableClock(START), 1);

        // Same subject, different organizations: each must own a separate counter.
        // If the organization half were dropped from the key, one of these first
        // calls would already be rejected.
        assertEquals(0, limiter.retryAfterSeconds("org-a:subject"));
        assertEquals(0, limiter.retryAfterSeconds("org-b:subject"));
        assertTrue(limiter.retryAfterSeconds("org-a:subject") > 0, "org-a exhausted independently");
        assertTrue(limiter.retryAfterSeconds("org-b:subject") > 0, "org-b exhausted independently");
    }

    @Test
    void retryAfterCountsDownWithinTheWindow() {
        MutableClock clock = new MutableClock(START);
        RateLimiter limiter = new RateLimiter(clock, 1);

        assertEquals(0, limiter.retryAfterSeconds("org-a:subject-1"));
        clock.advance(Duration.ofSeconds(30));
        assertEquals(30, limiter.retryAfterSeconds("org-a:subject-1"));
    }

    @Test
    void evictsWindowsOlderThanTwoMinutes() throws Exception {
        MutableClock clock = new MutableClock(START);
        RateLimiter limiter = new RateLimiter(clock, 2);

        assertEquals(0, limiter.retryAfterSeconds("stale"));
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        assertEquals(0, limiter.retryAfterSeconds("fresh"));

        Map<String, ?> windows = windows(limiter);
        assertEquals(1, windows.size());
        assertTrue(windows.containsKey("fresh"));
        assertFalse(windows.containsKey("stale"));
    }
}
