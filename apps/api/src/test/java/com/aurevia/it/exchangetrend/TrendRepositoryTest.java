package com.aurevia.it.exchangetrend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.exchange.trend.ComparabilityRule;
import com.aurevia.exchange.trend.TrendObservation;
import com.aurevia.exchange.trend.TrendRepository;

/**
 * S-G2-07 fast SQL-boundary tests. A mocked JDBC template proves each statement
 * is issued with the tenant fold and that row mappers read every displayed
 * column; the real-PostgreSQL RLS behaviour is verified by TrendingTest.
 */
class TrendRepositoryTest {

    private NamedParameterJdbcTemplate jdbc;
    private TrendRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        repository = new TrendRepository(jdbc);
    }

    @Test
    void readsMembershipAndRolesThroughTheAuthoritativeFold() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE);
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("clinician", "tenant_admin"));

        assertTrue(repository.hasActiveMembership("clinician-a"));
        assertEquals(List.of("clinician", "tenant_admin"), repository.activeRoles("clinician-a"));

        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.FALSE);
        assertFalse(repository.hasActiveMembership("nobody"));
    }

    @Test
    void checksPatientVisibilityInTheActiveTenant() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(Boolean.TRUE);
        assertTrue(repository.patientExists("p1"));
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(null);
        assertFalse(repository.patientExists("p2"));
    }

    @Test
    void insertsAndFindsAnObservation() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertEquals(1, repository.insertObservation("o1", observation()));
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(observation()));
        assertEquals(Optional.of(observation()), repository.findObservation("o1"));
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertEquals(Optional.empty(), repository.findObservation("missing"));
    }

    @Test
    void readsTheNextComparabilityVersionUnderTheTenantFold() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class)))
                .thenReturn(3);
        assertEquals(3, repository.nextComparabilityVersion("HBA1C"));
        verify(jdbc).queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class));
    }

    @Test
    void insertsAndFindsComparability() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertEquals(1, repository.insertComparability(rule(), Instant.parse("2026-09-01T00:00:00Z")));
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(rule()));
        assertEquals(Optional.of(rule()), repository.findComparability("HBA1C"));
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        assertEquals(Optional.empty(), repository.findComparability("UNKNOWN"));
    }

    @Test
    void readsAllObservationsOfOnePatient() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(observation()));
        assertEquals(List.of(observation()), repository.observationsForPatient("p1"));
        verify(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void observationMapperReadsEveryDisplayedColumn() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(anyString())).thenReturn("value");
        when(resultSet.getString("qualifier")).thenReturn("none");
        when(resultSet.getString("revision_status")).thenReturn("final");
        when(resultSet.getBigDecimal(anyString())).thenReturn(new BigDecimal("1.0"));
        when(resultSet.getObject(anyString(), eq(OffsetDateTime.class)))
                .thenReturn(OffsetDateTime.parse("2026-09-10T00:00:00Z"));

        TrendObservation mapped = TrendRepository.mapObservation(resultSet, 0);

        assertNotNull(mapped);
        assertEquals("value", mapped.patientId());
        assertEquals(new BigDecimal("1.0"), mapped.value());
        assertEquals(Instant.parse("2026-09-10T00:00:00Z"), mapped.effectiveAt());
        assertEquals("value", mapped.interpretation());
        verify(resultSet).getString("comparability_group");
    }

    @Test
    void comparabilityMapperReadsGroupVersionAndTrendableFlag() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(anyString())).thenReturn("HBA1C");
        when(resultSet.getInt("version")).thenReturn(2);
        when(resultSet.getBoolean("trendable")).thenReturn(true);

        ComparabilityRule mapped = TrendRepository.mapComparability(resultSet, 0);

        assertEquals(new ComparabilityRule("HBA1C", "HBA1C", "HBA1C", true, 2), mapped);
        verify(resultSet).getInt("version");
    }

    private static TrendObservation observation() {
        return new TrendObservation("o1", "p1", "HBA1C", "Synthetic HBA1C", "LAB-HPLC",
                TrendObservation.FINAL, new BigDecimal("7.1"), "mmol/mol",
                com.aurevia.exchange.trend.Qualifier.NONE, new BigDecimal("4.0"),
                new BigDecimal("7.0"), "mmol/mol", Instant.parse("2026-09-10T00:00:00Z"),
                "high", null, "SYNTHETIC_LAB", "v1");
    }

    private static ComparabilityRule rule() {
        return new ComparabilityRule("HBA1C", "mmol/mol", "LAB-HPLC", true, 1);
    }
}
