package com.aurevia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Fails startup closed when the {@code production} profile is combined with an
 * unsafe runtime configuration (WEB-01, WEB-05, ARC-01).
 *
 * <p>A production process must never inherit the synthetic authentication
 * header, run without a database password, or open a non-TLS database
 * connection. The checks run during bean initialization so the application
 * context fails to start. Values are read only from the environment and
 * Spring properties; no credential is stored in the repository and no message
 * ever includes the password.</p>
 */
@Component
@Profile("production")
public final class ProductionSafetyCheck {

    /** Message for a production process that also enables the test authentication profile. */
    static final String SYNTHETIC_PROFILE_MESSAGE =
            "The production profile must not be combined with the synthetic-auth profile";

    /** Message for a missing database password. */
    static final String PASSWORD_MESSAGE = "OREVIA_DB_PASSWORD must be set in the production profile";

    /** Message for a datasource URL that does not require TLS. */
    static final String TLS_MESSAGE =
            "spring.datasource.url must require TLS (sslmode=require or verify-full) in the production profile";

    private final Environment environment;
    private final String databasePassword;
    private final String datasourceUrl;

    /**
     * Bind the runtime configuration used by the startup validation.
     *
     * @param environment     Spring environment used for profile checks
     * @param databasePassword password from {@code OREVIA_DB_PASSWORD}, empty when unset
     * @param datasourceUrl   datasource URL from {@code spring.datasource.url}
     */
    public ProductionSafetyCheck(Environment environment,
            @Value("${OREVIA_DB_PASSWORD:}") String databasePassword,
            @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.environment = environment;
        this.databasePassword = databasePassword;
        this.datasourceUrl = datasourceUrl;
    }

    /**
     * Reject an unsafe production configuration so the application context fails to start.
     *
     * @throws IllegalStateException when the synthetic profile is active, the
     *                               database password is blank, or the datasource
     *                               URL does not require TLS
     */
    @PostConstruct
    void verifyProductionSafety() {
        if (environment.acceptsProfiles(Profiles.of("synthetic-auth"))) {
            throw new IllegalStateException(SYNTHETIC_PROFILE_MESSAGE);
        }
        if (databasePassword.isBlank()) {
            throw new IllegalStateException(PASSWORD_MESSAGE);
        }
        if (!datasourceUrl.contains("sslmode=require") && !datasourceUrl.contains("verify-full")) {
            throw new IllegalStateException(TLS_MESSAGE);
        }
    }
}
