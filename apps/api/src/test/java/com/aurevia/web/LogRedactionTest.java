package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.observability.CorrelationIdFilter;
import com.aurevia.observability.RequestLogCapture;
import com.aurevia.tenant.TenantContext;

/**
 * WEB-06 acceptance test for log redaction and request correlation.
 *
 * <p>A synthetic request carries a body marker and a bearer-token marker in
 * addition to a valid correlation id. The request reaches a route that always
 * returns an error, so the failure path is exercised through the normal servlet
 * entry point: the correlation filter, the tenant interceptor and the uniform
 * error handler all run. The test proves that the console output carries the
 * correlation id yet contains neither the body marker nor the token marker.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CORRELATION = "222e2222-2222-2222-2222-222222222222";
    private static final String BODY_MARKER = "ZZ-REDACTION-MARKER";
    private static final String TOKEN_MARKER = "ZZ-TOKEN-MARKER";
    private static final String MDC_KEY = "correlationId";

    /** Error-returning probe; only identifiers may reach a log line. */
    @RestController
    static class FailingController {
        /**
         * Always fail so the error path is observed end to end.
         *
         * @return never returns; raises a synthetic domain failure
         */
        @PostMapping("/api/v1/probe")
        String fail() {
            throw new ApiException(422, "SYNTHETIC_FAILURE", "synthetic failure");
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        MDC.remove(MDC_KEY);
    }

    @Test
    void errorRequestLogsCorrelationIdButNeitherBodyNorTokenMarker(CapturedOutput output)
            throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        TenantContextInterceptor interceptor = new TenantContextInterceptor(environment);
        Clock clock = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiErrorHandler())
                .addFilters(new CorrelationIdFilter(clock))
                .addInterceptors(interceptor)
                .build();

        mvc.perform(post("/api/v1/probe")
                        .header("X-Synthetic-Subject", "subject-1")
                        .header("X-Active-Organization", ORG)
                        .header("X-Correlation-Id", CORRELATION)
                        .header("Authorization", "Bearer " + TOKEN_MARKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + BODY_MARKER + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.correlationId").value(CORRELATION));

        String all = output.getAll();
        assertFalse(all.contains(BODY_MARKER), all);
        assertFalse(all.contains(TOKEN_MARKER), all);
        assertEquals(CORRELATION, RequestLogCapture.requestLog(output).get("correlationId").asString(),
                "the request log line must carry the correlation id: " + all);
    }
}
