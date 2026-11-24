package com.aurevia.web;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the instance-local rate limiter from the shared application clock and
 * the configured per-subject allowance (WEB-04). The application's Clock bean
 * is injected so limiter windows can be pinned in tests.
 */
@Configuration
public class RateLimiterConfiguration {

    /**
     * Create the limiter shared by all protected API requests in this instance.
     *
     * @param clock     shared application clock
     * @param perMinute configured calls allowed per key and minute
     * @return the instance-local limiter
     */
    @Bean
    RateLimiter rateLimiter(Clock clock,
                            @Value("${orevia.rate-limit.per-minute:600}") int perMinute) {
        return new RateLimiter(clock, perMinute);
    }
}
