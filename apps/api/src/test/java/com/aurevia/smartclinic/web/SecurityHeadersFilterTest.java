package com.aurevia.smartclinic.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WEB-02 HTTP adapter tests for the browser-defence response headers.
 *
 * <p>Standalone MockMvc with a single tiny controller keeps the proof at the
 * servlet boundary: every response must carry the baseline defensive headers,
 * API responses must additionally opt out of caching, and transport security
 * must only be advertised when the request actually arrived over TLS.</p>
 */
class SecurityHeadersFilterTest {

    /** Minimal endpoints standing in for a versioned API route and a health probe. */
    @RestController
    static class ProbeController {
        /**
         * @return a synthetic body for the versioned route
         */
        @GetMapping("/api/v1/x")
        String api() {
            return "ok";
        }

        /**
         * @return a synthetic body for the unauthenticated health route
         */
        @GetMapping("/health/live")
        String health() {
            return "UP";
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new SecurityHeadersFilter())
                .build();
    }

    @Test
    void everyResponseCarriesTheBaselineDefensiveHeaders() throws Exception {
        mvc.perform(get("/api/v1/x"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy",
                        "camera=(), microphone=(), geolocation=()"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
    }

    @Test
    void apiResponsesAreNotStored() throws Exception {
        mvc.perform(get("/api/v1/x"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void healthResponsesHaveNoNoStoreDirective() throws Exception {
        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Cache-Control"));
    }

    @Test
    void hstsIsAdvertisedOnlyOnSecureRequests() throws Exception {
        mvc.perform(get("/api/v1/x"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));

        mvc.perform(get("/api/v1/x").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security",
                        "max-age=31536000; includeSubDomains"));
    }

    /**
     * The docs subtree gets a same-origin policy so local bundles can run,
     * without inline or remote script sources.
     *
     * @throws Exception when the request fails
     */
    @Test
    void swaggerPathGetsScopedCsp() throws Exception {
        mvc.perform(get("/swagger-ui/"))
                .andExpect(header().string("Content-Security-Policy", allOf(
                        containsString("script-src 'self'"),
                        containsString("style-src 'self'"),
                        containsString("connect-src 'self'"),
                        containsString("frame-ancestors 'none'"),
                        not(containsString("unsafe-inline")),
                        not(containsString("unsafe-eval")))));
    }

    /**
     * The exact docs path without a trailing slash is scoped too, so the two
     * sides of the path predicate are both exercised.
     *
     * @throws Exception when the request fails
     */
    @Test
    void exactSwaggerPathGetsScopedCsp() throws Exception {
        mvc.perform(get("/swagger-ui"))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("script-src 'self'")));
    }
}
