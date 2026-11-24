package com.aurevia.chart.record;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.tenant.TenantContext;

/**
 * Both outcomes of the verification compare-and-set, without a database.
 *
 * <p>The service rejects a stale version before it reaches the repository, so the
 * "no row matched" outcome occurs only when a concurrent verifier wins between
 * the read and the update. A mocked row count makes that outcome deterministic.</p>
 */
class ChartRepositoryVerifyTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private ChartRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        repository = new ChartRepository(jdbc);
        TenantContext.set(new TenantContext(ORG, "clinician-a", TenantContext.PURPOSE, "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void verifyReportsTrueWhenExactlyOneRowWasPromoted() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertTrue(repository.verify("22222222-2222-2222-2222-222222222222", 1, "clinician-a", AT));
    }

    @Test
    void verifyReportsFalseWhenNoRowMatchedTheVersion() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        assertFalse(repository.verify("22222222-2222-2222-2222-222222222222", 1, "clinician-a", AT));
    }
}
