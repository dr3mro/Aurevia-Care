package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiErrorHandler;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import jakarta.servlet.Filter;

/**
 * HTTP-level evidence that the production resource-server chain validates the
 * token audience as well as the issuer (WEB-03, WEB-05).
 *
 * <p>The signing key is generated in memory for each test and never written to
 * the repository. The decoder is built from the matching public key, so token
 * verification and claim validation run locally without issuer discovery or a
 * network call.</p>
 */
class AudienceValidationHttpTest {

    private static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String AUDIENCE_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";
    private static final String ISSUER = "https://issuer.example.test";
    private static final String OTHER_ISSUER = "https://other-issuer.example.test";
    private static final String AUDIENCE = "synthetic-api";
    private static final String OTHER_AUDIENCE = "another-api";

    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;

    /** Minimal protected endpoint used to observe admission. */
    @RestController
    static class ProbeController {
        /** @return a fixed marker for a read */
        @GetMapping("/probe")
        public String read() {
            return "ok";
        }
    }

    /** Generate a fresh synthetic signing key pair for every test. */
    @BeforeEach
    void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
        privateKey = (RSAPrivateKey) pair.getPrivate();
    }

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void tokenWithMatchingIssuerAndAudienceIsAccepted() throws Throwable {
        newRunner(AUDIENCE).run(context -> mvc(context).perform(get("/probe")
                        .header("Authorization", "Bearer " + signedToken(ISSUER, AUDIENCE)))
                .andExpect(status().isOk()));
    }

    @Test
    void validTokenForAnotherAudienceIsRejected() throws Throwable {
        newRunner(AUDIENCE).run(context -> mvc(context).perform(get("/probe")
                        .header("Authorization", "Bearer " + signedToken(ISSUER, OTHER_AUDIENCE)))
                .andExpect(status().isUnauthorized()));
    }

    @Test
    void validTokenWithoutAudienceIsRejected() throws Throwable {
        newRunner(AUDIENCE).run(context -> mvc(context).perform(get("/probe")
                        .header("Authorization", "Bearer " + signedToken(ISSUER, null)))
                .andExpect(status().isUnauthorized()));
    }

    @Test
    void validAudienceFromAnotherIssuerIsRejected() throws Throwable {
        newRunner(AUDIENCE).run(context -> mvc(context).perform(get("/probe")
                        .header("Authorization", "Bearer " + signedToken(OTHER_ISSUER, AUDIENCE)))
                .andExpect(status().isUnauthorized()));
    }

    @Test
    void blankAudienceFailsStartup() {
        newRunner("   ").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertTrue(messageChain(failure).contains("AUREVIA_OIDC_AUDIENCE must be set"),
                    () -> "expected the audience fail-closed message, got: " + messageChain(failure));
        });
    }

    private WebApplicationContextRunner newRunner(String audience) {
        return new WebApplicationContextRunner()
                .withUserConfiguration(SecurityConfiguration.class, DocsEnabled.class)
                .withBean("localJwtDecoder", JwtDecoder.class,
                        () -> NimbusJwtDecoder.withPublicKey(publicKey).build())
                .withPropertyValues(
                        ISSUER_PROPERTY + "=" + ISSUER,
                        AUDIENCE_PROPERTY + "=" + audience);
    }

    private MockMvc mvc(ApplicationContext context) {
        SecurityFilterChain chain = context.getBean(SecurityFilterChain.class);
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiErrorHandler())
                .addFilters(chain.getFilters().toArray(new Filter[0]))
                .build();
    }

    private String signedToken(String tokenIssuer, String tokenAudience) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("user-123")
                .issuer(tokenIssuer)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
        if (tokenAudience != null) {
            claims.audience(List.of(tokenAudience));
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(privateKey));
        return jwt.serialize();
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable cause = failure;
        while (cause != null) {
            messages.append(cause.getMessage()).append('\n');
            cause = cause.getCause();
        }
        return messages.toString();
    }
}
