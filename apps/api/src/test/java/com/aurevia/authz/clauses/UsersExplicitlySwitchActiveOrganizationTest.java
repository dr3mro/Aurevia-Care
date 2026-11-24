package com.aurevia.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * IAM-01 clause: users explicitly switch active organization.
 *
 * <p>The active tenant is never inferred from the body, a resource identifier
 * or a first membership. On every protected request the caller names the
 * organization through the {@code X-Active-Organization} header, and the tenant
 * boundary binds it only after checking the selection against the verified
 * token's organization claims. This test drives that rule through a normal HTTP
 * entry point with a synthetic token and a probe controller, so a switch is
 * observable as the bound tenant changing between two requests made by the same
 * subject.</p>
 */
class UsersExplicitlySwitchActiveOrganizationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String ORG_OTHER = "33333333-3333-3333-3333-333333333333";
    private static final String SUBJECT = "user-123";

    /** Request attribute where spring-security-test stores the post-processed context. */
    private static final String SECURITY_CONTEXT_ATTRIBUTE =
            "org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors"
                    + "$SecurityContextRequestPostProcessorSupport$TestSecurityContextRepository.REPO";

    /** Probe that echoes the tenant context bound by the interceptor. */
    @RestController
    static class ProbeController {
        /**
         * @return the tenant fields observable before the interceptor clears them
         */
        @GetMapping("/api/v1/probe")
        public Map<String, String> probe() {
            TenantContext context = TenantContext.current();
            return Map.of("organizationId", context.organizationId(), "subject", context.subject());
        }
    }

    /**
     * Loads the security context that the spring-security test post-processor
     * places on the request into the holder, as the real security filter chain
     * does. This keeps the clause test on the interceptor's binding rule alone.
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
        org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment();
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

    private static org.springframework.security.oauth2.jwt.Jwt tokenEntitledTo(Object organizations) {
        return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("synthetic-token")
                .header("alg", "none")
                .subject(SUBJECT)
                .issuer("https://issuer.example.test")
                .issuedAt(Instant.parse("2026-11-21T00:00:00Z"))
                .expiresAt(Instant.parse("2026-11-21T01:00:00Z"))
                .claim("orgs", organizations)
                .build();
    }

    @Test
    void sameUserExplicitlySwitchesActiveOrganizationBetweenRequests() throws Exception {
        mvc.perform(get("/api/v1/probe")
                        .with(jwt().jwt(tokenEntitledTo(List.of(ORG_A, ORG_B))))
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(ORG_A))
                .andExpect(jsonPath("$.subject").value(SUBJECT));

        mvc.perform(get("/api/v1/probe")
                        .with(jwt().jwt(tokenEntitledTo(List.of(ORG_A, ORG_B))))
                        .header("X-Active-Organization", ORG_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(ORG_B))
                .andExpect(jsonPath("$.subject").value(SUBJECT));
        assertNull(TenantContext.current(), "the request boundary must not leak the last switch");
    }

    @Test
    void switchToOrganizationNotEntitledByTokenIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/probe")
                        .with(jwt().jwt(tokenEntitledTo(List.of(ORG_A))))
                        .header("X-Active-Organization", ORG_OTHER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_PERMITTED"));
        assertNull(TenantContext.current());
    }

    @Test
    void switchWithoutActiveOrganizationHeaderIsRejected() throws Exception {
        mvc.perform(get("/api/v1/probe")
                        .with(jwt().jwt(tokenEntitledTo(List.of(ORG_A)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_SELECTION"));
        assertNull(TenantContext.current());
    }

    @Test
    void switchWithMalformedActiveOrganizationHeaderIsRejected() throws Exception {
        mvc.perform(get("/api/v1/probe")
                        .with(jwt().jwt(tokenEntitledTo(List.of(ORG_A))))
                        .header("X-Active-Organization", "not-a-uuid"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_SELECTION"));
        assertNull(TenantContext.current());
    }
}
