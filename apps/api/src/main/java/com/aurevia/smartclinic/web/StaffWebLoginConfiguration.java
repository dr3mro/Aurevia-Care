package com.aurevia.smartclinic.web;

import static org.springframework.security.config.Customizer.withDefaults;

import java.time.Clock;
import java.time.Duration;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Browser OIDC login for the staff web application (WEB-02, WEB-03).
 *
 * <p>This chain sits before the stateless bearer chain and matches only
 * requests without an {@code Authorization: Bearer} header, so mobile and
 * partner clients keep their existing token flow and never inherit browser
 * session semantics. It uses the authorization-code flow with an HTTP-only,
 * secure session cookie, keeps CSRF enabled with a readable {@code XSRF-TOKEN}
 * cookie, and ends the session on the server on logout.</p>
 *
 * <p>All OIDC values come from {@code SMARTCLINIC_OIDC_*} environment
 * variables through the environment-backed properties. Startup fails closed
 * when any value is missing outside {@code synthetic-auth}; no credential is
 * ever stored in the repository. The identity provider must publish the
 * standard {@code /authorize}, {@code /token} and {@code /userinfo} endpoints
 * beneath the configured issuer, and its user-info response must carry the
 * same {@code sub} and {@code orgs} claims the bearer path validates.</p>
 *
 * <p>The exact {@code GET /health/live} and {@code GET /health/ready} probes are
 * permitted here and in the bearer chain because they carry no bearer header and
 * therefore reach this higher-priority chain first (WEB-05). The
 * {@code GET /health/metrics} scrape is permitted here too but is gated by source
 * address in {@link com.aurevia.smartclinic.web.MetricsController} (WEB-06).</p>
 *
 * <p>The exact {@code GET /api/v1/openapi.yaml} document is permitted here as
 * well: an anonymous browser client reaches this chain first, and the document
 * is static API documentation with no tenant or patient data (section 7).</p>
 */
@Configuration
@Profile("!synthetic-auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StaffWebLoginConfiguration {

    /** Registration identifier used by the staff web client. */
    static final String REGISTRATION_ID = "smartclinic";

    /** Absolute session ceiling; the idle ceiling is set by server.servlet.session.timeout. */
    private static final Duration ABSOLUTE_SESSION_LIFETIME = Duration.ofHours(12);

    private final String clientId;
    private final String clientSecret;
    private final String issuerUri;
    private final String sessionCookieName;

    /**
     * Bind the OIDC client settings and the session cookie name from the environment.
     *
     * @param clientId         client id from {@code SMARTCLINIC_OIDC_CLIENT_ID}
     * @param clientSecret     client secret from {@code SMARTCLINIC_OIDC_CLIENT_SECRET}
     * @param issuerUri        issuer from {@code SMARTCLINIC_OIDC_ISSUER_URI}
     * @param sessionCookieName configured session cookie name
     */
    public StaffWebLoginConfiguration(
            @Value("${smartclinic.oidc.client-id:}") String clientId,
            @Value("${smartclinic.oidc.client-secret:}") String clientSecret,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.issuerUri = issuerUri;
        this.sessionCookieName = sessionCookieName;
    }

    /**
     * Build the staff web client registration without network discovery.
     *
     * @return repository holding the single authorization-code registration
     * @throws IllegalStateException when an OIDC setting is missing
     */
    @Bean
    ClientRegistrationRepository staffClientRegistrationRepository() {
        if (Stream.of(clientId, clientSecret, issuerUri).anyMatch(String::isBlank)) {
            throw new IllegalStateException(
                    "SMARTCLINIC_OIDC_CLIENT_ID, SMARTCLINIC_OIDC_CLIENT_SECRET and "
                            + "SMARTCLINIC_OIDC_ISSUER_URI must be set");
        }
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri(issuerUri + "/authorize")
                .tokenUri(issuerUri + "/token")
                .userInfoUri(issuerUri + "/userinfo")
                .userNameAttributeName("sub")
                .scope("openid", "profile")
                .clientName("SmartClinic")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    /**
     * Build the browser security chain, ordered ahead of the bearer chain.
     *
     * @param http  shared web security builder
     * @param clock injected clock for the absolute session lifetime guard
     * @return the staff web filter chain
     * @throws Exception when the chain cannot be built
     */
    @Bean
    @Order(1)
    SecurityFilterChain staffWebSecurityFilterChain(HttpSecurity http, Clock clock) throws Exception {
        return http
                .securityMatcher(request -> !SecurityConfiguration.isBearerRequest(request))
                // A null request-attribute name makes the handler resolve the
                // deferred token on every response, so XSRF-TOKEN is actually
                // written for the single-page client to read.
                .csrf(csrf -> {
                    CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
                    handler.setCsrfRequestAttributeName(null);
                    csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                            .csrfTokenRequestHandler(handler);
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        // WEB-05/WEB-06: probes carry no bearer header, so they meet this chain
                        // first; permit exact paths so they never reach the login entry point.
                        // Section 7: the static OpenAPI document is public too.
                        .requestMatchers(HttpMethod.GET, "/health/live", "/health/ready", "/health/metrics",
                                "/api/v1/openapi.yaml").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(withDefaults())
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/logout"))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(sessionCookieName, "XSRF-TOKEN")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_OK))
                        .permitAll())
                .addFilterBefore(new AbsoluteSessionTimeoutFilter(clock, ABSOLUTE_SESSION_LIFETIME),
                        AuthorizationFilter.class)
                .build();
    }
}
