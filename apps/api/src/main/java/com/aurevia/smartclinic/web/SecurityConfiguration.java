package com.aurevia.smartclinic.web;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP security chains for the two supported authentication modes.
 *
 * <p>Under the {@code synthetic-auth} profile every request is permitted by the
 * filter chain and {@link com.aurevia.smartclinic.web.TenantContextInterceptor} remains the only gate,
 * reading synthetic headers exactly as before. In every other profile the
 * filter chain is a stateless OIDC resource server: a valid JWT bearer token is
 * required and {@link com.aurevia.smartclinic.web.TenantContextInterceptor} binds the active organization
 * from verified claims. The staff web cookie session is a separate later
 * chain, so CSRF is skipped only for requests that carry a bearer header,
 * which browsers never attach by themselves (WEB-02, WEB-03).</p>
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    private final String issuerUri;

    /**
     * Read the configured issuer; an absent property resolves to an empty string.
     *
     * @param issuerUri configured OIDC issuer, possibly empty
     */
    public SecurityConfiguration(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        this.issuerUri = issuerUri;
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
     * <p>Fails closed at startup when the issuer is missing: without a trusted
     * issuer there is no way to verify a token, so the application must not
     * accept requests.</p>
     *
     * @param http shared web security builder
     * @return the OIDC filter chain
     * @throws Exception when the chain cannot be built
     * @throws IllegalStateException when no OIDC issuer is configured
     */
    @Bean
    @Profile("!synthetic-auth")
    SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http) throws Exception {
        if (issuerUri.isBlank()) {
            throw new IllegalStateException("SMARTCLINIC_OIDC_ISSUER_URI must be set");
        }
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.ignoringRequestMatchers(SecurityConfiguration::isBearerRequest))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()))
                .build();
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
