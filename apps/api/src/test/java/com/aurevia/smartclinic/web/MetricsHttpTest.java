package com.aurevia.smartclinic.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * WEB-06 HTTP evidence for the complete servlet and security chain.
 *
 * <p>Under the production chains the metrics route is unauthenticated but
 * address-gated; under the synthetic profile a route with a path variable proves
 * the filter records the matched pattern and never a raw id. The decoder stub
 * keeps issuer discovery local and all addresses are synthetic.</p>
 */
class MetricsHttpTest {

    private static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCE_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";
    private static final String ISSUER = "https://issuer.example.test";

    /** Minimal route with an id so the raw path differs from the route pattern. */
    @RestController
    static class PatientProbeController {
        /** @return a fixed marker for a read */
        @GetMapping("/api/v1/patients/{id}")
        public String read() {
            return "ok";
        }
    }

    /** Stubs prevent issuer discovery and pin the session clock. */
    @TestConfiguration
    static class Stubs {
        /** @return a decoder that never performs network discovery */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                    .header("alg", "none").subject("user-123").issuer(ISSUER).build();
        }

        /** @return a fixed clock so absolute-session expiry is deterministic */
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    private RequestMetrics metrics;

    /** A fresh registry per test keeps series counts deterministic. */
    @BeforeEach
    void createRegistry() {
        metrics = new RequestMetrics();
    }

    /** Security and tenant state must not leak between runner invocations. */
    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void productionChainsServeMetricsOnlyFromAllowedAddresses() throws Throwable {
        newProductionRunner().run(context -> {
            assertNull(context.getStartupFailure());
            MockMvc mvc = mvc(context);

            String body = mvc.perform(get("/health/metrics"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("smartclinic_http_requests_total"), body);
            assertTrue(body.contains("# TYPE smartclinic_http_request_duration_seconds histogram"), body);

            // The filter records after the response is written, so the first scrape
            // has only metadata; a second scrape exposes its bucket samples.
            String secondBody = mvc.perform(get("/health/metrics"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(secondBody.contains("smartclinic_http_request_duration_seconds_bucket{"), secondBody);

            String deniedBody = mvc.perform(get("/health/metrics")
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.10");
                                return request;
                            }))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertEquals("", deniedBody);

            // We did not loosen the protected API while permitting the metrics route.
            mvc.perform(get("/api/v1/patients/patient-777"))
                    .andExpect(status().is3xxRedirection());
        });
    }

    @Test
    void matchedRouteIsRecordedWithoutRawPathIds() throws Throwable {
        newSyntheticRunner().run(context -> {
            assertNull(context.getStartupFailure());
            MockMvc mvc = mvc(context);

            mvc.perform(get("/api/v1/patients/patient-777")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/patients/patient-888")).andExpect(status().isOk());

            String body = mvc.perform(get("/health/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                    .andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("route=\"/api/v1/patients/{id}\",status=\"2xx\"} 2"), body);
            assertFalse(body.contains("patient-777"), body);
            assertFalse(body.contains("patient-888"), body);

            // Timed requests increment cumulative buckets with +Inf equal to the counter,
            // and no raw id reaches any bucket line.
            assertTrue(body.contains("smartclinic_http_request_duration_seconds_bucket{"), body);
            assertTrue(body.contains("smartclinic_http_request_duration_seconds_bucket{method=\"GET\","
                    + "route=\"/api/v1/patients/{id}\",status=\"2xx\",le=\"+Inf\"} 2\n"), body);
            assertTrue(body.contains("smartclinic_http_request_duration_seconds_count{method=\"GET\","
                    + "route=\"/api/v1/patients/{id}\",status=\"2xx\"} 2\n"), body);
            assertFalse(body.contains("patient-777"), body);
            assertFalse(body.contains("patient-888"), body);
        });
    }

    private static WebApplicationContextRunner newProductionRunner() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, StaffWebLoginConfiguration.class, Stubs.class)
                .withPropertyValues(
                        ISSUER_PROPERTY + "=" + ISSUER,
                        AUDIENCE_PROPERTY + "=synthetic-api",
                        "smartclinic.oidc.client-id=synthetic-client",
                        "smartclinic.oidc.client-secret=synthetic-secret");
    }

    private static WebApplicationContextRunner newSyntheticRunner() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, Stubs.class)
                .withPropertyValues("spring.profiles.active=synthetic-auth");
    }

    private MockMvc mvc(ApplicationContext context) {
        FilterChainProxy proxy = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        return MockMvcBuilders.standaloneSetup(
                        new MetricsController(metrics, new MetricsAccessPolicy("")),
                        new PatientProbeController())
                .addFilters(new RequestMetricsFilter(metrics), proxy)
                .build();
    }
}
