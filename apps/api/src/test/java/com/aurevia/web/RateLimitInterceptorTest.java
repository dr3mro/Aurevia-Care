package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.tenant.TenantContext;

/**
 * HTTP-level proof that an over-limit subject receives 429 with a Retry-After
 * header through the normal interceptor entry point (WEB-04).
 */
class RateLimitInterceptorTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "subject-1";

    /** Minimal protected route reached when the limiter admits the call. */
    @RestController
    static class ProbeController {
        /**
         * @return a fixed body; the probe only exists to observe interceptor decisions
         */
        @GetMapping("/api/v1/probe")
        String probe() {
            return "ok";
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void overLimitRequestReturns429WithRetryAfterHeader() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        TenantContextInterceptor interceptor = new TenantContextInterceptor(environment,
                new RateLimiter(Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC), 1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(interceptor)
                .build();

        mvc.perform(get("/api/v1/probe")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/probe")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // Spring skips afterCompletion when preHandle throws, so the rejected
        // request must not leave a tenant context bound to the servlet thread.
        assertNull(TenantContext.current(), "a rejected request must not leak tenant context");
    }
}
