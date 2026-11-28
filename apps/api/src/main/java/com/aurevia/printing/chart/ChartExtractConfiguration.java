package com.aurevia.printing.chart;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aurevia.printing.HandoutService;

/**
 * Spring wiring for the consolidated chart extract.
 *
 * <p>The extractor is a plain domain object so its release rules and section
 * choices can be tested without a container. This configuration is the only
 * place that assembles its collaborators. The print endpoint and preview that
 * call this bean are delivered by the print web slice; keeping the dependency
 * injection here lets that surface receive the extractor by injection.</p>
 */
@Configuration
public class ChartExtractConfiguration {

    /**
     * Expose the chart extract service as a Spring bean.
     *
     * @param handoutService shared immutable versioned PDF renderer
     * @param clock          shared server clock
     * @return a ready-to-use chart extract service
     */
    @Bean
    ChartExtractService chartExtractService(HandoutService handoutService, Clock clock) {
        return new ChartExtractService(handoutService, clock);
    }
}
