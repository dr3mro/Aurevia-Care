package com.aurevia.exchange.trend;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The SQL boundary for XCH-07 observations and comparability policy.
 *
 * <p>Every statement runs inside the tenant-guarded transaction; forced RLS
 * confines it to the active organization. Both tables are append-only for the
 * runtime role, and the author/approver columns are derived from the
 * transaction actor rather than accepted from the caller.</p>
 */
@Repository
public class TrendRepository {

    private static final String SELECT_OBSERVATION = """
            SELECT id::text AS id, patient_id::text AS patient_id, test_code, test_display,
                   comparability_group, revision_status, value, unit, qualifier,
                   reference_low, reference_high, reference_unit, effective_at,
                   interpretation, qualitative_value, source_system, source_version
              FROM public.trend_observation
            """;

    private static final RowMapper<TrendObservation> OBSERVATION_MAPPER = TrendRepository::mapObservation;
    private static final RowMapper<ComparabilityRule> RULE_MAPPER = TrendRepository::mapComparability;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public TrendRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership using the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest effective membership is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject("SELECT public.has_active_membership(:subject)",
                new MapSqlParameterSource().addValue("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List role keys currently in force for the subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList("SELECT role_key FROM public.active_roles(:subject)",
                new MapSqlParameterSource().addValue("subject", subject), String.class);
    }

    /**
     * Check that a patient is visible in the active organization.
     *
     * @param patientId patient identifier
     * @return {@code true} when the patient record exists in this tenant
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))
                """, new MapSqlParameterSource().addValue("patientId", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Insert one observation; the author is forced from the transaction actor.
     *
     * @param id          new opaque observation identifier
     * @param observation validated observation content
     * @return number of inserted rows
     */
    public int insertObservation(String id, TrendObservation observation) {
        return jdbc.update("""
                INSERT INTO public.trend_observation
                    (organization_id, id, patient_id, test_code, test_display, comparability_group,
                     revision_status, value, unit, qualifier, reference_low, reference_high,
                     reference_unit, effective_at, interpretation, qualitative_value,
                     source_system, source_version)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:patientId AS uuid), :testCode,
                        :testDisplay, :comparabilityGroup, :revisionStatus, :value, :unit,
                        :qualifier, :referenceLow, :referenceHigh, :referenceUnit,
                        CAST(:effectiveAt AS timestamptz), :interpretation, :qualitativeValue,
                        :sourceSystem, :sourceVersion)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("patientId", observation.patientId())
                .addValue("testCode", observation.testCode())
                .addValue("testDisplay", observation.testDisplay())
                .addValue("comparabilityGroup", observation.comparabilityGroup())
                .addValue("revisionStatus", observation.revisionStatus())
                .addValue("value", observation.value()).addValue("unit", observation.unit())
                .addValue("qualifier", observation.qualifier().wire())
                .addValue("referenceLow", observation.referenceLow())
                .addValue("referenceHigh", observation.referenceHigh())
                .addValue("referenceUnit", observation.referenceUnit())
                .addValue("effectiveAt", observation.effectiveAt().toString())
                .addValue("interpretation", observation.interpretation())
                .addValue("qualitativeValue", observation.qualitativeValue())
                .addValue("sourceSystem", observation.sourceSystem())
                .addValue("sourceVersion", observation.sourceVersion()));
    }

    /**
     * Read one observation without locking it.
     *
     * @param id observation identifier
     * @return the observation when visible
     */
    public Optional<TrendObservation> findObservation(String id) {
        return jdbc.query(SELECT_OBSERVATION + " WHERE id = CAST(:id AS uuid)",
                new MapSqlParameterSource().addValue("id", id), OBSERVATION_MAPPER).stream().findFirst();
    }

    /**
     * Allocate the next policy version under an advisory lock scoped to the
     * tenant and test code.
     *
     * @param testCode test identity
     * @return next positive version number
     */
    public int nextComparabilityVersion(String testCode) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT public.next_trend_comparability_version(:testCode)",
                new MapSqlParameterSource().addValue("testCode", testCode), Integer.class),
                "version fold returned null");
    }

    /**
     * Insert one comparability policy version; the approver is forced from the
     * transaction actor.
     *
     * @param rule          rule to persist
     * @param effectiveFrom effective instant
     * @return number of inserted rows
     */
    public int insertComparability(ComparabilityRule rule, Instant effectiveFrom) {
        return jdbc.update("""
                INSERT INTO public.trend_comparability
                    (organization_id, test_code, version, canonical_unit, comparability_group,
                     trendable, effective_from)
                VALUES (public.app_org(), :testCode, :version, :canonicalUnit, :comparabilityGroup,
                        :trendable, CAST(:effectiveFrom AS timestamptz))
                """, new MapSqlParameterSource()
                .addValue("testCode", rule.testCode()).addValue("version", rule.version())
                .addValue("canonicalUnit", rule.canonicalUnit())
                .addValue("comparabilityGroup", rule.comparabilityGroup())
                .addValue("trendable", rule.trendable())
                .addValue("effectiveFrom", effectiveFrom.toString()));
    }

    /**
     * Read the latest effective comparability rule through the version fold.
     *
     * @param testCode test identity
     * @return the effective rule, if one is established
     */
    public Optional<ComparabilityRule> findComparability(String testCode) {
        return jdbc.query("""
                SELECT test_code, version, canonical_unit, comparability_group, trendable
                  FROM public.current_trend_comparability(:testCode)
                """, new MapSqlParameterSource().addValue("testCode", testCode),
                RULE_MAPPER).stream().findFirst();
    }

    /**
     * Read every observation of one patient in the active tenant.
     *
     * @param patientId patient identifier
     * @return all visible observations, oldest effective time first
     */
    public List<TrendObservation> observationsForPatient(String patientId) {
        return jdbc.query(SELECT_OBSERVATION
                        + " WHERE patient_id = CAST(:patientId AS uuid) ORDER BY effective_at, id",
                new MapSqlParameterSource().addValue("patientId", patientId), OBSERVATION_MAPPER);
    }

    /**
     * Map one observation row, including every XCH-07 display column.
     *
     * @param resultSet source row
     * @param rowNumber row ordinal
     * @return the mapped observation
     * @throws SQLException when the row cannot be read
     */
    public static TrendObservation mapObservation(ResultSet resultSet, int rowNumber) throws SQLException {
        OffsetDateTime effective = resultSet.getObject("effective_at", OffsetDateTime.class);
        return new TrendObservation(resultSet.getString("id"), resultSet.getString("patient_id"),
                resultSet.getString("test_code"), resultSet.getString("test_display"),
                resultSet.getString("comparability_group"), resultSet.getString("revision_status"),
                resultSet.getBigDecimal("value"), resultSet.getString("unit"),
                Qualifier.from(resultSet.getString("qualifier")),
                resultSet.getBigDecimal("reference_low"), resultSet.getBigDecimal("reference_high"),
                resultSet.getString("reference_unit"),
                effective.toInstant(), resultSet.getString("interpretation"),
                resultSet.getString("qualitative_value"), resultSet.getString("source_system"),
                resultSet.getString("source_version"));
    }

    /**
     * Map one comparability rule row.
     *
     * @param resultSet source row
     * @param rowNumber row ordinal
     * @return the mapped rule
     * @throws SQLException when the row cannot be read
     */
    public static ComparabilityRule mapComparability(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ComparabilityRule(resultSet.getString("test_code"),
                resultSet.getString("canonical_unit"), resultSet.getString("comparability_group"),
                resultSet.getBoolean("trendable"), resultSet.getInt("version"));
    }
}
