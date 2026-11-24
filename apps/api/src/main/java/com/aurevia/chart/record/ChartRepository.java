package com.aurevia.chart.record;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The SQL boundary for the CLN-03 chart slice.
 *
 * <p>Every statement runs inside the tenant-guarded transaction, so forced RLS
 * confines it to the active organization. No organization or actor value is
 * accepted from a caller; the organization is bound transaction-locally.</p>
 */
@Repository
public class ChartRepository {

    private static final RowMapper<ChartEntry> ENTRY_MAPPER = ChartRepository::mapEntry;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public ChartRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership using the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest effective membership is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)",
                new MapSqlParameterSource("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List role keys currently in force for the subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)",
                new MapSqlParameterSource("subject", subject), String.class);
    }

    /**
     * Check that a patient is visible in the active tenant.
     *
     * @param patientId patient identifier
     * @return {@code true} when the patient exists and is visible
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))",
                new MapSqlParameterSource("patientId", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Look up one effective, approved entry in a versioned catalog family.
     *
     * @param family catalog family, for example {@code diagnosis}
     * @param code   tenant-local catalog code
     * @return the effective snapshot, or empty when unmapped
     */
    public Optional<CatalogHit> lookupCatalog(String family, String code) {
        String key = "catalog." + family + "." + code;
        return jdbc.query("""
                SELECT key, value, version FROM public.effective_catalog_family(:prefix)
                 WHERE key = :key
                """, new MapSqlParameterSource()
                        .addValue("prefix", "catalog." + family + ".")
                        .addValue("key", key),
                (resultSet, rowNumber) -> {
                    String fullKey = resultSet.getString("key");
                    return new CatalogHit(fullKey.substring(fullKey.lastIndexOf('.') + 1),
                            resultSet.getString("value"), resultSet.getInt("version"));
                }).stream().findFirst();
    }

    /**
     * Insert one chart entry.
     *
     * @param entry fully populated entry, including its derived identifiers
     */
    public void insert(ChartEntry entry) {
        jdbc.update("""
                INSERT INTO public.chart_entry
                    (organization_id, id, patient_id, encounter_id, type, original_text,
                     original_code, code_system, mapped_code, mapped_text, catalog_version,
                     source, presence, unknown_reason, verification, verified_by, verified_at,
                     effective_from, effective_to, measured_at, value, unit, clinical_author,
                     data_entry_actor, version, recorded_at)
                VALUES
                    (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:patientId AS uuid),
                     :encounterId, :type, :originalText, :originalCode, :codeSystem,
                     :mappedCode, :mappedText, :catalogVersion, :source, :presence,
                     :unknownReason, :verification, :verifiedBy,
                     CAST(:verifiedAt AS timestamptz),
                     CAST(:effectiveFrom AS timestamptz),
                     CAST(:effectiveTo AS timestamptz),
                     CAST(:measuredAt AS timestamptz), :value, :unit,
                     :clinicalAuthor, :dataEntryActor, :version,
                     CAST(:recordedAt AS timestamptz))
                """, new MapSqlParameterSource()
                .addValue("organizationId", orgId())
                .addValue("id", entry.id())
                .addValue("patientId", entry.patientId())
                .addValue("encounterId", entry.encounterId())
                .addValue("type", entry.type().name())
                .addValue("originalText", entry.originalText())
                .addValue("originalCode", entry.originalCode())
                .addValue("codeSystem", entry.codeSystem())
                .addValue("mappedCode", entry.mappedCode())
                .addValue("mappedText", entry.mappedText())
                .addValue("catalogVersion", entry.catalogVersion())
                .addValue("source", entry.source().name())
                .addValue("presence", entry.presence().name())
                .addValue("unknownReason", entry.unknownReason())
                .addValue("verification", entry.verification().name())
                .addValue("verifiedBy", entry.verifiedBy())
                // Bind instants as UTC OffsetDateTime (pgjdbc maps it to
                // timestamptz); the explicit CAST in the SQL keeps the value
                // unambiguous without a timezone-dependent shift.
                .addValue("verifiedAt", instantValue(entry.verifiedAt()))
                .addValue("effectiveFrom", instantValue(entry.effectiveFrom()))
                .addValue("effectiveTo", instantValue(entry.effectiveTo()))
                .addValue("measuredAt", instantValue(entry.measuredAt()))
                .addValue("value", entry.value())
                .addValue("unit", entry.unit())
                .addValue("clinicalAuthor", entry.clinicalAuthor())
                .addValue("dataEntryActor", entry.dataEntryActor())
                .addValue("version", entry.version())
                .addValue("recordedAt", instantValue(entry.recordedAt())));
    }

    /**
     * Atomically verify an unverified entry at an expected version.
     *
     * <p>The single conditional update is the compare-and-set: a concurrent or
     * replayed verification finds no matching row and returns zero, so exactly
     * one caller can promote a version to verified. An entry whose recorder left
     * clinical authorship open acquires the verifying clinician as its author;
     * the service returns the same value so view and row stay identical.</p>
     *
     * @param id        entry identifier
     * @param expected  version the caller read
     * @param actor     verifying subject
     * @param verifiedAt verification instant
     * @return {@code true} when this call performed the transition
     */
    public boolean verify(String id, int expected, String actor, Instant verifiedAt) {
        return jdbc.update("""
                UPDATE public.chart_entry
                   SET verification = 'VERIFIED', verified_by = :actor,
                       verified_at = CAST(:verifiedAt AS timestamptz),
                       clinical_author = COALESCE(clinical_author, :actor),
                       version = version + 1
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND id = CAST(:id AS uuid)
                   AND version = :expected
                   AND verification = 'UNVERIFIED'
                """, new MapSqlParameterSource()
                        .addValue("organizationId", orgId())
                        .addValue("id", id)
                        .addValue("expected", expected)
                        .addValue("actor", actor)
                        .addValue("verifiedAt", instantValue(verifiedAt))) == 1;
    }

    /**
     * Read one chart entry.
     *
     * @param id entry identifier
     * @return the visible entry, or empty when concealed
     */
    public Optional<ChartEntry> find(String id) {
        return jdbc.query("""
                SELECT id, patient_id, encounter_id, type, original_text, original_code,
                       code_system, mapped_code, mapped_text, catalog_version, source,
                       presence, unknown_reason, verification, verified_by, verified_at,
                       effective_from, effective_to, measured_at, value, unit,
                       clinical_author, data_entry_actor, version, recorded_at
                  FROM public.chart_entry WHERE id = CAST(:id AS uuid)
                """, new MapSqlParameterSource("id", id), ENTRY_MAPPER).stream().findFirst();
    }

    /**
     * List a patient's chart entries in deterministic clinical-time order.
     *
     * @param patientId patient identifier
     * @return visible entries, newest clinical time first
     */
    public List<ChartEntry> chart(String patientId) {
        return jdbc.query("""
                SELECT id, patient_id, encounter_id, type, original_text, original_code,
                       code_system, mapped_code, mapped_text, catalog_version, source,
                       presence, unknown_reason, verification, verified_by, verified_at,
                       effective_from, effective_to, measured_at, value, unit,
                       clinical_author, data_entry_actor, version, recorded_at
                  FROM public.chart_entry WHERE patient_id = CAST(:patientId AS uuid)
                 ORDER BY COALESCE(effective_from, recorded_at) DESC, id
                """, new MapSqlParameterSource("patientId", patientId), ENTRY_MAPPER);
    }

    private static ChartEntry mapEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ChartEntry(
                resultSet.getString("id"),
                resultSet.getString("patient_id"),
                resultSet.getString("encounter_id"),
                EntryType.valueOf(resultSet.getString("type")),
                resultSet.getString("original_text"),
                resultSet.getString("original_code"),
                resultSet.getString("code_system"),
                resultSet.getString("mapped_code"),
                resultSet.getString("mapped_text"),
                resultSet.getObject("catalog_version", Integer.class),
                EntrySource.valueOf(resultSet.getString("source")),
                PresenceState.valueOf(resultSet.getString("presence")),
                resultSet.getString("unknown_reason"),
                VerificationState.valueOf(resultSet.getString("verification")),
                resultSet.getString("verified_by"),
                instant(resultSet, "verified_at"),
                instant(resultSet, "effective_from"),
                instant(resultSet, "effective_to"),
                instant(resultSet, "measured_at"),
                resultSet.getString("value"),
                resultSet.getString("unit"),
                resultSet.getString("clinical_author"),
                resultSet.getString("data_entry_actor"),
                resultSet.getInt("version"),
                instant(resultSet, "recorded_at"));
    }

    // pgjdbc cannot infer a SQL type for a bare java.time.Instant parameter and
    // a text parameter is not implicitly cast to timestamptz on INSERT. Bind a
    // UTC OffsetDateTime instead: pgjdbc maps it to timestamptz without a
    // timezone-dependent shift. The INSERT/UPDATE keep their explicit
    // CAST(... AS timestamptz) so the bound value is unambiguous either way.
    private static OffsetDateTime instantValue(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column) == null
                ? null
                : resultSet.getTimestamp(column).toInstant();
    }

    private static String orgId() {
        return com.aurevia.tenant.TenantContext.current().organizationId();
    }

    /** An effective catalog mapping snapshot.
     *
     * @param code    tenant-local mapped code
     * @param text    mapped display text
     * @param version effective catalog version
     */
    public record CatalogHit(String code, String text, int version) {
    }
}
