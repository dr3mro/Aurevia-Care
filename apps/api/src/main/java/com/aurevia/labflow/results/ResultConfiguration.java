package com.aurevia.labflow.results;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aurevia.lab.result.LabPolicyCatalog;
import com.aurevia.lab.result.LabResultService;

/**
 * Wiring for the LAB-02 report kernel used by the result-entry API.
 *
 * <p>The HTTP service derives every abnormal and critical flag from the
 * site-approved bounds carried on each command, so the kernel itself holds no
 * implicit thresholds: an empty catalog means it preserves the interpretation
 * supplied at the boundary. The kernel owns only revision append and state
 * transitions; its events are not a delivery channel.</p>
 */
@Configuration
public class ResultConfiguration {

    /**
     * Expose the append-only report kernel as an injectable bean.
     *
     * @param clock shared application clock
     * @return a kernel with no implicit range or critical policy
     */
    @Bean
    LabResultService labResultService(Clock clock) {
        return new LabResultService(new LabPolicyCatalog(List.of(), List.of()), clock);
    }
}
