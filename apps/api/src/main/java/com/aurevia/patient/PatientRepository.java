package com.aurevia.patient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.tenant.TenantContext;

/**
 * The single SQL boundary for patient registration.
 *
 * <p>V7 applies forced RLS and append-only triggers to the patient table. Every
 * read uses the effective-version fold; writes are INSERTs through the guarded
 * transaction.</p>
 */
@Repository
public class PatientRepository {

    /** Business purpose stamped on patient administration audit events (OPS-02). */
    public static final String PURPOSE = "PATIENT_ADMIN";
    /** Audit scope for patient events (OPS-02). */
    public static final String SCOPE = "PATIENT";

    private static final RowMapper<Patient> PATIENT_MAPPER = PatientRepository::mapPatient;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public PatientRepository(NamedParameterJdbcTemplate jdbc) {
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
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
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
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Insert a new patient record; the author and version are database-owned.
     *
     * @param organizationId active organization identifier
     * @param id             opaque patient identifier
     * @param mrn            issuer-scoped MRN
     * @param issuer         MRN issuer
     * @param displayName    original spelling
     * @param normalizedName normalized search form
     * @param dob            date of birth, or {@code null}
     * @param unknownReason  coded unknown reason, or {@code null}
     * @param language       patient language
     * @param contacts       contact JSON
     * @param identifiers    identity JSON
     * @param consents       consent JSON
     * @return number of inserted rows
     */
    public int insert(String organizationId, String id, String mrn, String issuer, String displayName,
                      String normalizedName, String dob, String unknownReason, String language,
                      String contacts, String identifiers, String consents) {
        return jdbc.update("""
                INSERT INTO public.patient_record
                    (organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name,
                     dob, dob_unknown_reason, language, contacts, identifiers, consents)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid),
                        public.next_patient_version(CAST(:id AS uuid)),
                        :mrn, :issuer, :displayName, :normalizedName,
                        CAST(:dob AS date), :unknownReason, :language,
                        CAST(:contacts AS jsonb), CAST(:identifiers AS jsonb), CAST(:consents AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId).addValue("id", id)
                .addValue("mrn", mrn).addValue("issuer", issuer)
                .addValue("displayName", displayName).addValue("normalizedName", normalizedName)
                .addValue("dob", dob).addValue("unknownReason", unknownReason)
                .addValue("language", language).addValue("contacts", contacts)
                .addValue("identifiers", identifiers).addValue("consents", consents));
    }

    /**
     * Check whether an MRN is already used for an issuer in the active tenant.
     *
     * @param issuer MRN issuer
     * @param mrn    medical record number
     * @return {@code true} when the issuer-scoped MRN already exists
     */
    public boolean mrnExists(String issuer, String mrn) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record
                                WHERE mrn_issuer = :issuer AND mrn = :mrn)
                """, Map.of("issuer", issuer, "mrn", mrn), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Find an effective patient by opaque identifier.
     *
     * @param id patient identifier
     * @return the effective patient when visible, otherwise empty
     */
    public Optional<Patient> findById(String id) {
        return jdbc.query("""
                SELECT id::text, version, mrn, mrn_issuer, display_name, normalized_name,
                       dob::text, dob_unknown_reason, language, contacts::text,
                       identifiers::text, consents::text
                  FROM public.current_patients() WHERE id = CAST(:id AS uuid)
                """, Map.of("id", id), PATIENT_MAPPER).stream().findFirst();
    }

    /**
     * Search effective patients by normalized name prefix.
     *
     * @param normalizedName normalized search form
     * @return matching effective patients ordered by normalized name
     */
    public List<Patient> search(String normalizedName) {
        return jdbc.query("""
                SELECT id::text, version, mrn, mrn_issuer, display_name, normalized_name,
                       dob::text, dob_unknown_reason, language, contacts::text,
                       identifiers::text, consents::text
                  FROM public.current_patients() WHERE normalized_name LIKE :name || '%'
                 ORDER BY normalized_name, id
                """, Map.of("name", normalizedName), PATIENT_MAPPER);
    }

    /**
     * Find effective patients with the same normalized name and date of birth.
     *
     * @param normalizedName normalized search form
     * @param dob            civil date of birth
     * @param excludeId      opaque id of the patient whose own record is excluded
     * @return possible duplicates, excluding the given patient
     */
    public List<Patient> likelyDuplicates(String normalizedName, String dob, String excludeId) {
        return jdbc.query("""
                SELECT id::text, version, mrn, mrn_issuer, display_name, normalized_name,
                       dob::text, dob_unknown_reason, language, contacts::text,
                       identifiers::text, consents::text
                  FROM public.current_patients()
                 WHERE normalized_name = :name AND dob = CAST(:dob AS date)
                   AND id <> CAST(:excludeId AS uuid)
                 ORDER BY id
                """, new MapSqlParameterSource().addValue("name", normalizedName)
                .addValue("dob", dob).addValue("excludeId", excludeId), PATIENT_MAPPER);
    }

    /**
     * Append one OPS-02 audit event inside the caller's transaction.
     *
     * @param context    active tenant context
     * @param action     patient action key
     * @param resourceId patient identifier addressed by the command
     * @param outcome    success, denied, not-found or conflict outcome
     */
    public void appendAudit(TenantContext context, String action, String resourceId, String outcome) {
        jdbc.update("""
                INSERT INTO public.audit_event
                    (organization_id, actor_id, action, resource_type, resource_id,
                     purpose, scope, correlation_id, outcome)
                VALUES (CAST(:organizationId AS uuid), NULL, :action, :resourceType, :resourceId,
                        :purpose, :scope, :correlationId, :outcome)
                """, new MapSqlParameterSource()
                .addValue("organizationId", context.organizationId())
                .addValue("action", action)
                .addValue("resourceType", SCOPE)
                .addValue("resourceId", resourceId)
                .addValue("purpose", PURPOSE)
                .addValue("scope", SCOPE)
                .addValue("correlationId", context.correlationId())
                .addValue("outcome", outcome));
    }

    private static Patient mapPatient(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Patient(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("mrn"), resultSet.getString("mrn_issuer"),
                resultSet.getString("display_name"), resultSet.getString("normalized_name"),
                resultSet.getString("dob"), resultSet.getString("dob_unknown_reason"),
                resultSet.getString("language"), resultSet.getString("contacts"),
                resultSet.getString("identifiers"), resultSet.getString("consents"));
    }
}
