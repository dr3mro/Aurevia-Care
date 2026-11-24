package com.aurevia.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP security chains for the two supported authentication modes.
 *
 * <p>Under the {@code synthetic-auth} profile every request is permitted by the
 * filter chain and {@link com.aurevia.web.TenantContextInterceptor} remains the only gate,
 * reading synthetic headers exactly as before. In every other profile the
 * filter chain is a stateless OIDC resource server: a valid JWT bearer token is
 * required and {@link com.aurevia.web.TenantContextInterceptor} binds the active organization
 * from verified claims. The staff web cookie session is a separate
 * {@code @Order(1)} chain that matches only non-bearer requests, so CSRF is
 * skipped only for requests that carry a bearer header, which browsers never
 * attach by themselves (WEB-02, WEB-03). Both production chains permit the
 * exact {@code GET /health/live} and {@code GET /health/ready} probes without a
 * token, plus {@code GET /health/metrics} which is gated by source address in
 * {@link com.aurevia.web.MetricsController}; all other routes require
 * authentication (WEB-05, WEB-06). The bearer chain
 * additionally fails startup without an audience and rejects tokens that do not
 * carry the configured audience, while keeping the issuer and timestamp checks
 * (WEB-03, WEB-05).</p>
 *
 * <p>The exact {@code GET /api/v1/openapi.yaml} contract document is also
 * public: it is static API documentation with no tenant or patient data, so it
 * needs no token. It still passes the tenant interceptor's rate limiter and the
 * browser-defence headers filter (section 7).</p>
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    private final String issuerUri;
    private final String audience;

    /**
     * Read the issuer and audience used to validate bearer tokens; an absent
     * property resolves to an empty string and fails startup closed outside the
     * synthetic profile.
     *
     * @param issuerUri configured OIDC issuer, possibly empty
     * @param audience  expected OIDC token audience, possibly empty
     */
    public SecurityConfiguration(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences:}") String audience) {
        this.issuerUri = issuerUri;
        this.audience = audience;
    }

    /**
     * Permissive chain for the synthetic header login used by existing tests.
     *
     * @param http shared web security builder
     * @return the synthetic filter chain
     * @throws Exception when the chain cannot be built
     */
    @Bean
    @Profile("synthetic-auth")
    SecurityFilterChain syntheticSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }

    /**
     * Stateless OIDC resource-server chain for all non-synthetic profiles.
     *
     * <p>Fails closed at startup when the issuer or the audience is missing:
     * without either value there is no way to decide whether a signed token was
     * intended for this service, so the application must not accept requests.
     * The decoder is wrapped so the issuer, timestamp and audience validators
     * run on every bearer token.</p>
     *
     * @param http        shared web security builder
     * @param jwtDecoder  the decoder supplied by the OAuth2 resource-server setup
     * @param docsEnabled shared decision on whether browser documentation is exposed
     * @return the OIDC filter chain
     * @throws Exception when the chain cannot be built
     * @throws IllegalStateException when no OIDC issuer or audience is configured
     */
    @Bean
    @Order(2)
    @Profile("!synthetic-auth")
    SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            DocsEnabled docsEnabled) throws Exception {
        if (issuerUri.isBlank()) {
            throw new IllegalStateException("OREVIA_OIDC_ISSUER_URI must be set");
        }
        if (audience.isBlank()) {
            throw new IllegalStateException("OREVIA_OIDC_AUDIENCE must be set");
        }
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.ignoringRequestMatchers(SecurityConfiguration::isBearerRequest))
                .authorizeHttpRequests(authorize -> {
                    // WEB-05/WEB-06: exact unauthenticated routes only; never a /health/** wildcard.
                    // Section 7: the static OpenAPI document is public API documentation.
                    authorize.requestMatchers(HttpMethod.GET, "/health/live", "/health/ready", "/health/metrics",
                            "/api/v1/openapi.yaml").permitAll();
                    if (docsEnabled.isEnabled()) {
                        // Static, tenant-free browser documentation; no permit rule exists when off.
                        authorize.requestMatchers(HttpMethod.GET, "/swagger-ui", "/swagger-ui/**").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(
                        withClaimValidation(jwtDecoder, issuerUri, audience))))
                .build();
    }

    /**
     * Wrap a decoder so that every decoded token must satisfy the default JWT
     * validators (type, timestamp and certificate thumbprint), the configured
     * issuer and the configured audience. A token with no {@code aud} claim, or
     * with an audience that does not contain the configured value, is rejected.
     *
     * @param delegate  decoder that verifies the token signature
     * @param issuerUri trusted issuer, never blank when this method is called
     * @param audience  required audience, never blank when this method is called
     * @return a decoder that validates signature and claims
     */
    static JwtDecoder withClaimValidation(JwtDecoder delegate, String issuerUri, String audience) {
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithValidators(
                new JwtIssuerValidator(issuerUri), new JwtAudienceValidator(audience));
        return token -> {
            Jwt jwt = delegate.decode(token);
            OAuth2TokenValidatorResult result = validator.validate(jwt);
            if (result.hasErrors()) {
                throw new BadJwtException("Token failed issuer or audience validation");
            }
            return jwt;
        };
    }

    /**
     * Decide whether a request carries a bearer credential.
     *
     * @param request current HTTP request
     * @return {@code true} only for an {@code Authorization: Bearer} header
     */
    static boolean isBearerRequest(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.startsWith("Bearer ");
    }
}
