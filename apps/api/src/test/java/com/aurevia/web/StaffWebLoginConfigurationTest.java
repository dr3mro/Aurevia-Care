package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Fail-closed configuration and synthetic client registration for the staff web
 * OIDC login (WEB-03). Every value here is a synthetic fixture; no real issuer,
 * client id or secret is present.
 */
class StaffWebLoginConfigurationTest {

    private static final String CLIENT_ID = "synthetic-client";
    private static final String CLIENT_SECRET = "synthetic-secret";
    private static final String ISSUER = "https://issuer.example.test";
    private static final String FAILURE_MESSAGE =
            "OREVIA_OIDC_CLIENT_ID, OREVIA_OIDC_CLIENT_SECRET and OREVIA_OIDC_ISSUER_URI must be set";

    @Test
    void blankClientIdFailsStartupClosed() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StaffWebLoginConfiguration("", CLIENT_SECRET, ISSUER, "JSESSIONID")
                        .staffClientRegistrationRepository());
        assertTrue(failure.getMessage().contains("OREVIA_OIDC_CLIENT_ID"));
        assertEquals(FAILURE_MESSAGE, failure.getMessage());
    }

    @Test
    void blankClientSecretFailsStartupClosed() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StaffWebLoginConfiguration(CLIENT_ID, "", ISSUER, "JSESSIONID")
                        .staffClientRegistrationRepository());
        assertTrue(failure.getMessage().contains("OREVIA_OIDC_CLIENT_SECRET"));
    }

    @Test
    void blankIssuerFailsStartupClosed() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StaffWebLoginConfiguration(CLIENT_ID, CLIENT_SECRET, "", "JSESSIONID")
                        .staffClientRegistrationRepository());
        assertTrue(failure.getMessage().contains("OREVIA_OIDC_ISSUER_URI"));
    }

    @Test
    void syntheticValuesProduceAnAuthorizationCodeRegistrationWithoutDiscovery() {
        ClientRegistrationRepository repository =
                new StaffWebLoginConfiguration(CLIENT_ID, CLIENT_SECRET, ISSUER, "JSESSIONID")
                        .staffClientRegistrationRepository();

        ClientRegistration registration = repository.findByRegistrationId("orevia");
        assertEquals("orevia", registration.getRegistrationId());
        assertEquals(CLIENT_ID, registration.getClientId());
        assertEquals(CLIENT_SECRET, registration.getClientSecret());
        assertEquals(AuthorizationGrantType.AUTHORIZATION_CODE, registration.getAuthorizationGrantType());
        assertEquals("{baseUrl}/login/oauth2/code/{registrationId}", registration.getRedirectUri());
        assertEquals(List.of("openid", "profile"), List.copyOf(registration.getScopes()));
        assertTrue(registration.getProviderDetails().getAuthorizationUri().startsWith(ISSUER));
        assertTrue(registration.getProviderDetails().getTokenUri().startsWith(ISSUER));
        assertTrue(registration.getProviderDetails().getUserInfoEndpoint().getUri().startsWith(ISSUER));
    }
}
