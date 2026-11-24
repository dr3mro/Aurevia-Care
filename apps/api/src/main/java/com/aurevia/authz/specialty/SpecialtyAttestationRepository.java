package com.aurevia.authz.specialty;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.authz.specialty.SpecialtyContract.Attestation;

/**
 * The single SQL boundary for S-G4-06 (ARC-01, ARC-02, OPS-02).
 *
 * <p>Every statement runs inside the tenant-guarded transaction, so forced row
 * level security confines it to the active organization. The attestation ledger
 * is append-only for every role and the subject and actor id are derived by the
 * V46 insert trigger from the transaction's {@code app.actor} binding, never
 * from a caller field.</p>
 */
@Repository
public class SpecialtyAttestationRepository {

    private static final String SELECT = """
            SELECT id::text AS id, version, patient_id::text AS patient_id, action_key, role_key,
                   decision, subject, actor_id::text AS actor_id, attested_revision, resource_ref,
                   attested_text, created_at
              FROM public.specialty_attestation
            """;
    private static final RowMapper<Attestation> MAPPER = SpecialtyAttestationRepository::map;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public SpecialtyAttestationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership with the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest membership event is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List the roles currently in force for a subject in the active tenant.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Check that a patient is visible in the active organization.
     *
     * @param patientId patient identifier
     * @return {@code true} when the patient exists in this tenant
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))
                """, Map.of("patientId", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Append one human attestation. Subject and actor are set by the V46 trigger.
     *
     * @param id               new opaque identifier
     * @param patientId        owning patient
     * @param actionKey        attested workflow action
     * @param roleKey          role that authorized the action
     * @param decision         PRD-06 decision derived from the action
     * @param attestedRevision revision the human read before attesting
     * @param resourceRef      local source reference
     * @param attestedText     human decision statement
     * @return number of inserted rows
     */
    public int insert(String id, String patientId, String actionKey, String roleKey,
                      SpecialtyDecision decision, int attestedRevision, String resourceRef,
                      String attestedText) {
        return jdbc.update("""
                INSERT INTO public.specialty_attestation
                    (organization_id, id, patient_id, action_key, role_key, decision,
                     attested_revision, resource_ref, attested_text)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:patientId AS uuid), :actionKey,
                        :roleKey, :decision, :attestedRevision, :resourceRef, :attestedText)
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("patientId", patientId).addValue("actionKey", actionKey)
                .addValue("roleKey", roleKey).addValue("decision", decision.wire())
                .addValue("attestedRevision", attestedRevision).addValue("resourceRef", resourceRef)
                .addValue("attestedText", attestedText));
    }

    /**
     * Read one attestation.
     *
     * @param id attestation identifier
     * @return the attestation when visible in the active tenant
     */
    public Optional<Attestation> find(String id) {
        return jdbc.query(SELECT + " WHERE id = CAST(:id AS uuid)", Map.of("id", id), MAPPER)
                .stream().findFirst();
    }

    /**
     * List attestations with stable tenant-scoped ordering.
     *
     * @param limit maximum number of rows
     * @return newest attestations first
     */
    public List<Attestation> list(int limit) {
        return jdbc.query(SELECT + " ORDER BY created_at DESC, id DESC LIMIT :limit",
                Map.of("limit", limit), MAPPER);
    }

    private static Attestation map(ResultSet resultSet, int rowNumber) throws SQLException {
        Instant occurred = resultSet.getObject("created_at", OffsetDateTime.class).toInstant();
        return new Attestation(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("patient_id"), resultSet.getString("action_key"),
                resultSet.getString("role_key"), SpecialtyDecision.from(resultSet.getString("decision")),
                resultSet.getString("subject"), resultSet.getString("actor_id"),
                resultSet.getInt("attested_revision"), resultSet.getString("resource_ref"),
                resultSet.getString("attested_text"), occurred);
    }
}
