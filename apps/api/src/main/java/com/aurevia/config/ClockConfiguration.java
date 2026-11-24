package com.aurevia.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time source for scheduling rules.
 *
 * <p>Booking rules such as the minimum notice and the hold lifetime must not read
 * the database clock, because a single command would then span two different
 * opinions of "now". The application clock is injected so tests can pin it.</p>
 */
@Configuration
public class ClockConfiguration {

    /**
     * Shared UTC clock.
     *
     * @return system UTC clock
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
