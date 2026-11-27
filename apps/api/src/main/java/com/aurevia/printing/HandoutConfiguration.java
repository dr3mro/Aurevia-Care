package com.aurevia.printing;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aurevia.documents.PdfRenderer;

/**
 * Spring wiring for the printing module.
 *
 * <p>The service is a plain domain object so its policy and versioning can be
 * tested without a container. This configuration is the only place that
 * assembles its collaborators; the print endpoints receive the resulting bean
 * by injection.</p>
 */
@Configuration
public class HandoutConfiguration {

    /**
     * Expose the handout service as a Spring bean.
     *
     * @param pdfRenderer shared deterministic PDF renderer
     * @param clock       shared server clock
     * @return a ready-to-use handout service
     */
    @Bean
    HandoutService handoutService(PdfRenderer pdfRenderer, Clock clock) {
        return new HandoutService(new HandoutRenderer(pdfRenderer), new LocalHandoutArchive(), clock);
    }
}
