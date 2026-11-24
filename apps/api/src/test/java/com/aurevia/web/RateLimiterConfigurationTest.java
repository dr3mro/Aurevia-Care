package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/** Proves the configured allowance, not just a directly constructed limiter, is enforced. */
class RateLimiterConfigurationTest {

    private static final Instant START = Instant.parse("2026-11-21T00:00:00Z");
    private static final String KEY = "org-a:subject-1";

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    /** Pinned clock so no test depends on wall time. */
    @Configuration
    static class FixedClockConfiguration {
        /**
         * @return a clock frozen at the synthetic start instant
         */
        @Bean
        Clock clock() {
            return Clock.fixed(START, ZoneOffset.UTC);
        }
    }

    private RateLimiter limiter(String configuredPerMinute) {
        context = new AnnotationConfigApplicationContext();
        MockEnvironment environment = new MockEnvironment();
        if (configuredPerMinute != null) {
            environment.setProperty("orevia.rate-limit.per-minute", configuredPerMinute);
        }
        context.setEnvironment(environment);
        context.register(FixedClockConfiguration.class, RateLimiterConfiguration.class);
        context.refresh();
        return context.getBean(RateLimiter.class);
    }

    @Test
    void configuredLimitIsEnforcedByTheBean() {
        RateLimiter limiter = limiter("2");
        assertEquals(0, limiter.retryAfterSeconds(KEY));
        assertEquals(0, limiter.retryAfterSeconds(KEY));
        assertTrue(limiter.retryAfterSeconds(KEY) > 0, "the configured third call must be rejected");
    }

    @Test
    void missingPropertyFallsBackToSixHundred() {
        RateLimiter limiter = limiter(null);
        for (int call = 0; call < 600; call++) {
            assertEquals(0, limiter.retryAfterSeconds(KEY));
        }
        assertTrue(limiter.retryAfterSeconds(KEY) > 0, "the 601st call must be rejected by default");
    }
}
