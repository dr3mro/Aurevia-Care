package com.aurevia.smartclinic.web;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.tenant.TenantContext;

import jakarta.servlet.Filter;

/**
 * HTTP entry-point evidence that the actual production filter chain requires a
 * JWT on protected routes and skips CSRF only for bearer requests (WEB-02,
 * WEB-03). The stub decoder prevents network discovery; all data is synthetic.
 */
class SecurityFilterChainHttpTest {

    private static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCE_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private JdbcTemplate jdbc;

    /** A synthetic, in-memory probe result so the readiness route needs no database. */
    @BeforeEach
    void stubProbeJdbc() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    }

    /** Minimal protected endpoint used to observe admission. */
    @RestController
    static class ProbeController {
        /** @return a fixed marker for a read */
        @GetMapping("/api/v1/probe")
        public String read() {
            return "ok";
        }

        /** @return a fixed marker for a write */
        @PostMapping("/api/v1/probe")
        public String write() {
            return "ok";
        }
    }

    /** Stub decoder prevents issuer discovery over the network. */
    @TestConfiguration
    static class DecoderStub {
        /** @return a decoder that never performs network discovery */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                    .header("alg", "none").subject("user-123").build();
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void unauthenticatedGetReturns401() throws Throwable {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DocsEnabled.class, DecoderStub.class)
                .withPropertyValues(ISSUER_PROPERTY + "=https://issuer.example.test", AUDIENCE_PROPERTY + "=synthetic-api")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    MockMvc mvc = mvc(context);
                    mvc.perform(get("/api/v1/probe").header("X-Active-Organization", ORG))
                            .andExpect(status().isUnauthorized());
                });
    }

    @Test
    void unauthenticatedLiveProbeReturns200() throws Throwable {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DocsEnabled.class, DecoderStub.class)
                .withPropertyValues(ISSUER_PROPERTY + "=https://issuer.example.test", AUDIENCE_PROPERTY + "=synthetic-api")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    mvc(context).perform(get("/health/live"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("UP"));
                });
    }

    @Test
    void unauthenticatedReadyProbeReturns200() throws Throwable {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DocsEnabled.class, DecoderStub.class)
                .withPropertyValues(ISSUER_PROPERTY + "=https://issuer.example.test", AUDIENCE_PROPERTY + "=synthetic-api")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    mvc(context).perform(get("/health/ready"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("UP"));
                });
    }

    @Test
    void postWithoutAuthorizationIsRejectedByCsrf() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DocsEnabled.class, DecoderStub.class)
                .withPropertyValues(ISSUER_PROPERTY + "=https://issuer.example.test", AUDIENCE_PROPERTY + "=synthetic-api")
                .run(context -> mvc(context).perform(post("/api/v1/probe")
                                .header("X-Active-Organization", ORG))
                        .andExpect(status().isForbidden()));
    }

    @Test
    void postWithNonBearerAuthorizationIsRejectedByCsrf() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DocsEnabled.class, DecoderStub.class)
                .withPropertyValues(ISSUER_PROPERTY + "=https://issuer.example.test", AUDIENCE_PROPERTY + "=synthetic-api")
                .run(context -> mvc(context).perform(post("/api/v1/probe")
                                .header("Authorization", "Basic dXNlcjpwYXNz")
                                .header("X-Active-Organization", ORG))
                        .andExpect(status().isForbidden()));
    }

    @Test
    void postWithBearerAuthorizationSkipsCsrfAndRequiresAuthentication() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DocsEnabled.class, DecoderStub.class)
                .withPropertyValues(ISSUER_PROPERTY + "=https://issuer.example.test", AUDIENCE_PROPERTY + "=synthetic-api")
                .run(context -> mvc(context).perform(post("/api/v1/probe")
                                .header("Authorization", "Bearer synthetic-token")
                                .header("X-Active-Organization", ORG))
                        .andExpect(status().isUnauthorized()));
    }

    private MockMvc mvc(org.springframework.context.ApplicationContext context) {
        SecurityFilterChain chain = context.getBean(SecurityFilterChain.class);
        return MockMvcBuilders.standaloneSetup(new ProbeController(), new HealthController(jdbc))
                .setControllerAdvice(new ApiErrorHandler())
                .addMappedInterceptors(new String[] {"/api/v1/**"},
                        new TenantContextInterceptor(context.getEnvironment()))
                .addFilters(chain.getFilters().toArray(new Filter[0]))
                .build();
    }
}
