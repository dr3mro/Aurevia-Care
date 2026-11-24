package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.tenant.TenantContext;

/**
 * HTTP entry-point evidence for the unauthenticated contract route (specification section 7).
 *
 * <p>The browser chain is {@code @Order(1)} and matches requests without a
 * bearer header, so an anonymous fetch meets it first; a stub decoder and fixed
 * clock keep the OIDC chain local. The interceptor carries a one-call limiter so
 * the public route is proven to stay rate limited without a sign-in. All data is
 * synthetic.</p>
 */
class OpenApiContractHttpTest {

    private static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCE_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";
    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "synthetic-api";
    private static final String CONTRACT_PATH = "/api/v1/openapi.yaml";

    /** Stubs prevent issuer discovery and pin the rate-limit window. */
    @TestConfiguration
    static class Stubs {
        /** @return a decoder that mints synthetic tokens with the configured issuer and audience */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                    .header("alg", "none").subject("user-123").issuer(ISSUER)
                    .claim("aud", List.of(AUDIENCE)).build();
        }

        /** @return a fixed clock so absolute-session expiry is deterministic */
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /**
     * An anonymous fetch returns the authoritative YAML with the defensive
     * headers and without any login or tenant selection.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void anonymousRequestReturnsAuthoritativeYamlWithSecurityHeaders() throws Throwable {
        byte[] authoritative = Files.readAllBytes(OpenApiContractDriftTest.locateContract());
        newRunner().run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get(CONTRACT_PATH))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/yaml"))
                    .andExpect(content().bytes(authoritative))
                    .andExpect(header().string("Content-Security-Policy",
                            "default-src 'none'; frame-ancestors 'none'"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Permissions-Policy",
                            "camera=(), microphone=(), geolocation=()"))
                    .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().exists("X-Correlation-Id"));
        });
    }

    /**
     * A valid bearer token is not required but also does not break the public
     * route: the OIDC chain permits the exact GET path.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void bearerRequestIsAlsoPermitted() throws Throwable {
        newRunner().run(context -> {
            assertNull(context.getStartupFailure());
            mvc(context).perform(get(CONTRACT_PATH).header("Authorization", "Bearer synthetic-token"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/yaml"));
        });
    }

    /**
     * Removing sign-in does not remove the instance-local rate limit: the second
     * anonymous fetch in the same window is rejected with Retry-After.
     *
     * @throws Throwable when the context runner fails
     */
    @Test
    void publicContractRouteRemainsRateLimited() throws Throwable {
        newRunner().run(context -> {
            assertNull(context.getStartupFailure());
            MockMvc mvc = mvc(context);
            mvc.perform(get(CONTRACT_PATH)).andExpect(status().isOk());
            mvc.perform(get(CONTRACT_PATH))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        });
    }

    /** @return a local runner with both production chains and stubbed OIDC settings */
    private static WebApplicationContextRunner newRunner() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class,
                        StaffWebLoginConfiguration.class, DocsEnabled.class, Stubs.class)
                .withPropertyValues(
                        ISSUER_PROPERTY + "=" + ISSUER,
                        AUDIENCE_PROPERTY + "=" + AUDIENCE,
                        "orevia.oidc.client-id=synthetic-client",
                        "orevia.oidc.client-secret=synthetic-secret");
    }

    /**
     * Wire the real security filters, headers filter and tenant interceptor into
     * a standalone MVC boundary around the contract controller.
     *
     * @param context running application context
     * @return an HTTP client for the contract route
     */
    private static MockMvc mvc(ApplicationContext context) {
        FilterChainProxy proxy = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        TenantContextInterceptor interceptor = new TenantContextInterceptor(context.getEnvironment(),
                new RateLimiter(Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC), 1));
        return MockMvcBuilders.standaloneSetup(new OpenApiContractController())
                .setControllerAdvice(new ApiErrorHandler())
                .addMappedInterceptors(new String[] {"/api/v1/**"}, interceptor)
                .addFilters(new SecurityHeadersFilter(), proxy)
                .build();
    }
}
