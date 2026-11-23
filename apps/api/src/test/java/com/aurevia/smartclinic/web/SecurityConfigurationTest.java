package com.aurevia.smartclinic.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Verifies both profile-scoped filter chains, the bearer-only CSRF exception
 * and the fail-closed issuer check (WEB-02, WEB-03, IAM-01).
 */
class SecurityConfigurationTest {

    private static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCE_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";
    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "synthetic-api";
    private static final String FAILURE_MESSAGE = "SMARTCLINIC_OIDC_ISSUER_URI must be set";
    private static final String AUDIENCE_FAILURE_MESSAGE = "SMARTCLINIC_OIDC_AUDIENCE must be set";

    /** Stops the issuer-based decoder from performing discovery during tests. */
    @TestConfiguration
    static class DecoderStub {
        /** @return a decoder that never performs network discovery */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("user-123")
                    .build();
        }
    }

    @Test
    void syntheticProfileBuildsAPermissiveChainWithoutAnIssuer() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DocsEnabled.class)
                .withPropertyValues("spring.profiles.active=synthetic-auth")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(SecurityFilterChain.class));
                });
    }

    @Test
    void productionProfileBuildsTheOidcChainWithAnIssuerAndAudience() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DecoderStub.class, DocsEnabled.class)
                .withPropertyValues(ISSUER_PROPERTY + "=" + ISSUER, AUDIENCE_PROPERTY + "=" + AUDIENCE)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(SecurityFilterChain.class));
                });
    }

    @Test
    void productionProfileFailsClosedWithoutAnIssuer() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DecoderStub.class, DocsEnabled.class)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(failureMessageChain(failure).contains(FAILURE_MESSAGE));
                });
    }

    @Test
    void productionProfileFailsClosedWithoutAnAudience() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DecoderStub.class, DocsEnabled.class)
                .withPropertyValues(ISSUER_PROPERTY + "=" + ISSUER)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(failureMessageChain(failure).contains(AUDIENCE_FAILURE_MESSAGE));
                });
    }

    @Test
    void blankIssuerIsRejectedDirectly() {
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new SecurityConfiguration("   ", AUDIENCE)
                        .oidcSecurityFilterChain(null, new DecoderStub().jwtDecoder(), disabledDocs()));
        assertEquals(FAILURE_MESSAGE, failure.getMessage());
    }

    @Test
    void blankAudienceIsRejectedDirectly() {
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new SecurityConfiguration(ISSUER, "   ")
                        .oidcSecurityFilterChain(null, new DecoderStub().jwtDecoder(), disabledDocs()));
        assertEquals(AUDIENCE_FAILURE_MESSAGE, failure.getMessage());
    }

    @Test
    void bearerHeaderIsRecognized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer synthetic-token");
        assertTrue(SecurityConfiguration.isBearerRequest(request));
    }

    @Test
    void nonBearerHeaderIsNotRecognized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        assertFalse(SecurityConfiguration.isBearerRequest(request));
    }

    @Test
    void missingHeaderIsNotRecognized() {
        assertFalse(SecurityConfiguration.isBearerRequest(new MockHttpServletRequest()));
    }

    /** @return a disabled flag built from an environment with no active profile */
    private static DocsEnabled disabledDocs() {
        return new DocsEnabled(new org.springframework.mock.env.MockEnvironment());
    }

    private static String failureMessageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable cause = failure;
        while (cause != null) {
            messages.append(cause.getMessage()).append('\n');
            cause = cause.getCause();
        }
        return messages.toString();
    }
}
