package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.tenant.TenantContext;

/**
 * WEB-05 HTTP evidence for the complete production chain set.
 *
 * <p>The browser chain is {@code @Order(1)} and matches every request without a
 * bearer header, so an unauthenticated health probe meets it before the OIDC
 * chain. A stub decoder and a fixed clock keep both chains local; the readiness
 * probe uses a synthetic in-memory template. All data is synthetic.</p>
 */
class HealthChainHttpTest {

    private static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCE_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";
    private static final String ISSUER = "https://issuer.example.test";

    /** Minimal protected endpoint used to observe that the API stays guarded. */
    @RestController
    static class ProtectedController {
        /** @return a fixed marker for a read */
        @GetMapping("/api/v1/probe")
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

    private JdbcTemplate jdbc;

    /** A synthetic, in-memory probe result so the readiness route needs no database. */
    @BeforeEach
    void stubProbeJdbc() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void liveProbePassesTheBrowserChainWithoutAuthentication() throws Throwable {
        newRunner().run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get("/health/live"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        });
    }

    @Test
    void readyProbePassesTheBrowserChainWithoutAuthentication() throws Throwable {
        newRunner().run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get("/health/ready"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        });
    }

    @Test
    void protectedRouteStillRequiresAuthenticationWithBothChains() throws Throwable {
        newRunner().run(context -> {
            assertNull(context.getStartupFailure());
            // No bearer header: the browser chain's OIDC login entry point redirects.
            mvc(context).perform(get("/api/v1/probe"))
                    .andExpect(status().is3xxRedirection());
        });
    }

    private static WebApplicationContextRunner newRunner() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DocsEnabled.class,
                        StaffWebLoginConfiguration.class, Stubs.class)
                .withPropertyValues(
                        ISSUER_PROPERTY + "=" + ISSUER,
                        AUDIENCE_PROPERTY + "=synthetic-api",
                        "orevia.oidc.client-id=synthetic-client",
                        "orevia.oidc.client-secret=synthetic-secret");
    }

    private MockMvc mvc(ApplicationContext context) {
        FilterChainProxy proxy = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        return MockMvcBuilders.standaloneSetup(new HealthController(jdbc), new ProtectedController())
                .setControllerAdvice(new ApiErrorHandler())
                .addMappedInterceptors(new String[] {"/api/v1/**"},
                        new TenantContextInterceptor(context.getEnvironment()))
                .addFilters(proxy)
                .build();
    }
}
