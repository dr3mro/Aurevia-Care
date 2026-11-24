package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.tenant.TenantContext;

import jakarta.servlet.http.Cookie;

/**
 * HTTP-level evidence for the staff web cookie session beside the bearer chain
 * (WEB-02, WEB-03, IAM-01): CSRF is enforced for browser requests, the browser
 * principal is bound to a tenant, stale sessions and logout are rejected on the
 * server, and bearer requests keep their old no-CSRF behaviour. All values are
 * synthetic.
 */
class BrowserSessionHttpTest {

    private static final String ISSUER = "https://issuer.example.test";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "user-123";
    private static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCE_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";
    private static final String AUDIENCE = "synthetic-api";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    /** Minimal protected endpoint reached only when every gate passes. */
    @RestController
    static class ProbeController {
        /** @return bound tenant fields for a read */
        @GetMapping("/api/v1/probe")
        public Map<String, String> read() {
            TenantContext context = TenantContext.current();
            return Map.of("organizationId", context.organizationId(), "subject", context.subject());
        }

        /** @return bound tenant fields for a state change */
        @PostMapping("/api/v1/probe")
        public Map<String, String> write() {
            TenantContext context = TenantContext.current();
            return Map.of("organizationId", context.organizationId(), "subject", context.subject());
        }
    }

    /** Stubs prevent issuer discovery and pin the session clock. */
    @TestConfiguration
    static class Stubs {
        /** @return a decoder that never contacts the network */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(SUBJECT)
                    .issuer(ISSUER)
                    .audience(List.of(AUDIENCE))
                    .claim("orgs", List.of(ORG))
                    .build();
        }

        /** @return a fixed clock so absolute-session expiry is deterministic */
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void stateChangeWithoutCsrfTokenReturns403() throws Throwable {
        newRunner().run(context -> {
            MockMvc mvc = mvc(context);
            MockHttpSession session = browserSession(NOW);
            mvc.perform(post("/api/v1/probe").session(session).header("X-Active-Organization", ORG))
                    .andExpect(status().isForbidden());
        });
    }

    @Test
    void stateChangeWithCsrfTokenReachesController() throws Throwable {
        newRunner().run(context -> {
            MockMvc mvc = mvc(context);
            MockHttpSession session = browserSession(NOW);
            Cookie csrf = csrfCookie(mvc);
            mvc.perform(post("/api/v1/probe")
                            .session(session)
                            .cookie(csrf)
                            .header("X-XSRF-TOKEN", csrf.getValue())
                            .header("X-Active-Organization", ORG))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.organizationId").value(ORG))
                    .andExpect(jsonPath("$.subject").value(SUBJECT));
        });
    }

    @Test
    void browserPrincipalWithoutOrganizationClaimIsRejected() throws Throwable {
        newRunner().run(context -> {
            MockMvc mvc = mvc(context);
            MockHttpSession session = browserSession(NOW, false);
            Cookie csrf = csrfCookie(mvc);
            mvc.perform(post("/api/v1/probe")
                            .session(session)
                            .cookie(csrf)
                            .header("X-XSRF-TOKEN", csrf.getValue())
                            .header("X-Active-Organization", ORG))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        });
    }

    @Test
    void sessionOlderThanTwelveHoursIsRejected() throws Throwable {
        newRunner().run(context -> {
            MockMvc mvc = mvc(context);
            MockHttpSession stale = browserSession(NOW.minus(Duration.ofHours(12)).minusMillis(1));
            Cookie csrf = csrfCookie(mvc);
            mvc.perform(post("/api/v1/probe")
                            .session(stale)
                            .cookie(csrf)
                            .header("X-XSRF-TOKEN", csrf.getValue())
                            .header("X-Active-Organization", ORG))
                    .andExpect(status().isUnauthorized());
            assertTrue(stale.isInvalid(), "the stale session must be invalidated on the server");
        });
    }

    @Test
    void logoutInvalidatesTheSessionAndClearsCookies() throws Throwable {
        newRunner().run(context -> {
            MockMvc mvc = mvc(context);
            MockHttpSession session = browserSession(NOW);
            Cookie csrf = csrfCookie(mvc);
            MvcResult result = mvc.perform(post("/logout")
                            .session(session)
                            .cookie(csrf)
                            .header("X-XSRF-TOKEN", csrf.getValue()))
                    .andExpect(status().isOk())
                    .andReturn();
            assertTrue(session.isInvalid(), "logout must end the session on the server");
            assertTrue(cookieHeader(result).contains("JSESSIONID="),
                    "logout must clear the session cookie: " + cookieHeader(result));
            assertTrue(cookieHeader(result).contains("XSRF-TOKEN="),
                    "logout must clear the CSRF cookie: " + cookieHeader(result));
        });
    }

    @Test
    void bearerRequestSkipsCsrfAndReachesController() throws Throwable {
        newRunner().run(context -> {
            MockMvc mvc = mvc(context);
            mvc.perform(post("/api/v1/probe")
                            .header("Authorization", "Bearer synthetic-token")
                            .header("X-Active-Organization", ORG))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.organizationId").value(ORG));
        });
    }

    private static WebApplicationContextRunner newRunner() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DocsEnabled.class,
                        StaffWebLoginConfiguration.class, Stubs.class)
                .withPropertyValues(
                        ISSUER_PROPERTY + "=" + ISSUER,
                        AUDIENCE_PROPERTY + "=" + AUDIENCE,
                        "orevia.oidc.client-id=synthetic-client",
                        "orevia.oidc.client-secret=synthetic-secret");
    }

    private static MockMvc mvc(org.springframework.context.ApplicationContext context) {
        FilterChainProxy proxy = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(context.getEnvironment()))
                .addFilters(proxy)
                .build();
    }

    /** Obtain the readable XSRF-TOKEN cookie the way the staff web client does. */
    private static Cookie csrfCookie(MockMvc mvc) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/probe").header("X-Active-Organization", ORG)).andReturn();
        Cookie csrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrf, "the browser chain must issue a readable XSRF-TOKEN cookie");
        assertFalse(csrf.isHttpOnly(), "the web client must be able to read XSRF-TOKEN");
        return csrf;
    }

    private static String cookieHeader(MvcResult result) {
        return String.join("; ", result.getResponse().getHeaders("Set-Cookie"));
    }

    /** A session carrying an authenticated browser principal with tenant claims. */
    private static MockHttpSession browserSession(Instant creationTime) {
        return browserSession(creationTime, true);
    }

    private static MockHttpSession browserSession(Instant creationTime, boolean withOrganizations) {
        long millis = creationTime.toEpochMilli();
        MockHttpSession session = new MockHttpSession() {
            @Override
            public long getCreationTime() {
                return millis;
            }
        };
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        Map<String, Object> attributes = withOrganizations
                ? Map.of("sub", SUBJECT, "orgs", List.of(ORG), "iss", ISSUER)
                : Map.of("sub", SUBJECT, "iss", ISSUER);
        OAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
        SecurityContext context = new SecurityContextImpl(
                new OAuth2AuthenticationToken(user, authorities, "orevia"));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
}
