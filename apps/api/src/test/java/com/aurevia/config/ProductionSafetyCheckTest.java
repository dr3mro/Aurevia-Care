package com.aurevia.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifies that the production profile fails startup closed on every unsafe
 * combination and starts on the safe ones (WEB-01, WEB-05, ARC-01).
 */
class ProductionSafetyCheckTest {

    private static final String PASSWORD = "synthetic-db-password-not-a-secret"; // synthetic-ok: fixed fixture, not a credential
    // Synthetic driver scheme: the guard inspects the TLS token, not the driver,
    // and a literal PostgreSQL URL would misclassify this unit test as a DB test.
    private static final String REQUIRE_URL = "jdbc:synthetic://db.example.test:5432/orevia?sslmode=require";
    private static final String VERIFY_FULL_URL =
            "jdbc:synthetic://db.example.test:5432/orevia?sslmode=verify-full";
    private static final String INSECURE_URL = "jdbc:synthetic://127.0.0.1:5432/orevia";

    private static final String SYNTHETIC_MESSAGE =
            "The production profile must not be combined with the synthetic-auth profile";
    private static final String PASSWORD_MESSAGE =
            "OREVIA_DB_PASSWORD must be set in the production profile";
    private static final String TLS_MESSAGE =
            "spring.datasource.url must require TLS (sslmode=require or verify-full) in the production profile";

    private ApplicationContextRunner runner(String profiles, String url, String password) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ProductionSafetyCheck.class)
                .withPropertyValues(
                        "spring.profiles.active=" + profiles,
                        "spring.datasource.url=" + url,
                        "OREVIA_DB_PASSWORD=" + password);
    }

    @Test
    void productionWithSyntheticAuthFailsStartup() {
        runner("production,synthetic-auth", REQUIRE_URL, PASSWORD).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertEquals(SYNTHETIC_MESSAGE, unsafeFailure(failure).getMessage());
        });
    }

    @Test
    void productionWithEmptyPasswordFailsStartup() {
        runner("production", REQUIRE_URL, "").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertEquals(PASSWORD_MESSAGE, unsafeFailure(failure).getMessage());
        });
    }

    @Test
    void productionWithInsecureDatasourceUrlFailsStartup() {
        runner("production", INSECURE_URL, PASSWORD).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertEquals(TLS_MESSAGE, unsafeFailure(failure).getMessage());
        });
    }

    @Test
    void productionWithRequireUrlStarts() {
        runner("production", REQUIRE_URL, PASSWORD).run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(ProductionSafetyCheck.class));
        });
    }

    @Test
    void productionWithVerifyFullUrlStarts() {
        runner("production", VERIFY_FULL_URL, PASSWORD).run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(ProductionSafetyCheck.class));
        });
    }

    @Test
    void checkIsNotCreatedWithoutTheProductionProfile() {
        runner("synthetic-auth", INSECURE_URL, "").run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean(ProductionSafetyCheck.class.getName()));
        });
    }

    @Test
    void failureMessagesNeverIncludeThePassword() {
        runner("production", INSECURE_URL, PASSWORD).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertFalse(messageChain(failure).contains(PASSWORD));
        });
    }

    @Test
    void productionProfileDeclaresTheHardenedRuntimeSettings() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-production.yml"));
        assertEquals(1, sources.size());
        PropertySource<?> source = sources.get(0);
        assertEquals("graceful", source.getProperty("server.shutdown"));
        assertEquals("30s", source.getProperty("spring.lifecycle.timeout-per-shutdown-phase"));
        assertEquals("10s", source.getProperty("server.tomcat.connection-timeout"));
        assertEquals("2MB", source.getProperty("server.tomcat.max-swallow-size"));
        assertEquals("${OREVIA_DB_POOL:20}",
                source.getProperty("spring.datasource.hikari.maximum-pool-size"));
        assertEquals("5000", String.valueOf(source.getProperty("spring.datasource.hikari.connection-timeout")));
        assertEquals("1500000", String.valueOf(source.getProperty("spring.datasource.hikari.max-lifetime")));
        assertEquals("20000", String.valueOf(source.getProperty("spring.datasource.hikari.leak-detection-threshold")));
        assertEquals("never", source.getProperty("server.error.include-message"));
        assertEquals("never", source.getProperty("server.error.include-stacktrace"));
        assertEquals("framework", source.getProperty("server.forward-headers-strategy"));
    }

    /** @return the fail-closed exception inside the context startup failure chain */
    private static IllegalStateException unsafeFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof IllegalStateException illegalState) {
                return illegalState;
            }
            cause = cause.getCause();
        }
        throw new AssertionError("No IllegalStateException in the startup failure chain", failure);
    }

    /** @return every message in the failure chain, joined for redaction assertions */
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
