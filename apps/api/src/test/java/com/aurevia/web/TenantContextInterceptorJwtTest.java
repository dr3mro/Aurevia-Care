package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Profiles;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.tenant.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * OIDC bearer-token binding at the tenant boundary: the interceptor accepts a
 * verified {@code JwtAuthenticationToken}, binds the selected organization from
 * token claims, and denies every other authentication outside the synthetic
 * profile (IAM-01, ARC-02, WEB-03).
 */
class TenantContextInterceptorJwtTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "user-123";

    /** Request attribute where spring-security-test stores the post-processed context. */
    private static final String SECURITY_CONTEXT_ATTRIBUTE =
            "org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors"
                    + "$SecurityContextRequestPostProcessorSupport$TestSecurityContextRepository.REPO";

    /** Tiny probe so the test can observe the request and its bound tenant context. */
    @RestController
    static class ProbeController {
        /**
         * @return the bound tenant fields, observable before the interceptor clears them
         */
        @GetMapping("/api/v1/probe")
        public java.util.Map<String, String> probe() {
            TenantContext context = TenantContext.current();
            return java.util.Map.of("organizationId", context.organizationId(),
                    "subject", context.subject(), "correlationId", context.correlationId());
        }
    }

    /**
     * Loads the security context that the spring-security test post-processors
     * place on the request into the holder, as the real security filter chain
     * does. This keeps the unit focused on the interceptor's binding rule.
     */
    private static final class SecurityContextBridgeFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            Object saved = request.getAttribute(SECURITY_CONTEXT_ATTRIBUTE);
            if (saved instanceof SecurityContext context) {
                SecurityContextHolder.setContext(context);
            }
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("oidc");
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .addFilters(new SecurityContextBridgeFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static org.springframework.security.oauth2.jwt.Jwt tokenWith(Object organizations) {
        var builder = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("synthetic-token")
                .header("alg", "none")
                .subject(SUBJECT)
                .issuer("https://issuer.example.test")
                .issuedAt(Instant.parse("2026-11-21T00:00:00Z"))
                .expiresAt(Instant.parse("2026-11-21T01:00:00Z"));
        if (organizations != null) {
            builder.claim("orgs", organizations);
        }
        return builder.build();
    }

    @Test
    void withoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/probe").header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertNull(TenantContext.current());
    }

    @Test
    void tokenWithoutOrganizationsReturns401() throws Exception {
        mvc.perform(get("/api/v1/probe")
                        .with(jwt().jwt(tokenWith(null)))
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tokenWithPermittedOrganizationReachesController() throws Exception {
        mvc.perform(get("/api/v1/probe")
                        .with(jwt().jwt(tokenWith(List.of(ORG))))
                        .header("X-Active-Organization", ORG)
                        .header("X-Correlation-Id", "corr-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(ORG))
                .andExpect(jsonPath("$.subject").value(SUBJECT))
                .andExpect(jsonPath("$.correlationId").value("corr-jwt"));
    }

    @Test
    void jwtOverLimitRequestReturns429WithRetryAfterHeader() throws Exception {
        // Drive the production OIDC branch (not just synthetic-auth) through a
        // limiter of one call so the 429 contract is proven on the real path.
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("oidc");
        MockMvc limitedMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment,
                        new RateLimiter(Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC), 1)))
                .addFilters(new SecurityContextBridgeFilter())
                .build();

        limitedMvc.perform(get("/api/v1/probe")
                        .with(jwt().jwt(tokenWith(List.of(ORG))))
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());

        limitedMvc.perform(get("/api/v1/probe")
                        .with(jwt().jwt(tokenWith(List.of(ORG))))
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        assertNull(TenantContext.current(), "a rejected JWT request must not leak tenant context");
    }

    @Test
    void organizationOutsideTokenClaimsReturns403() throws Exception {
        mvc.perform(get("/api/v1/probe")
                        .with(jwt().jwt(tokenWith(List.of(ORG))))
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_PERMITTED"));
    }

    @Test
    void nonJwtAuthenticationReturns401() throws Exception {
        mvc.perform(get("/api/v1/probe")
                        .with(user("staff"))
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
