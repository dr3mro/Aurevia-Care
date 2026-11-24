package com.aurevia.patient.consolidation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.patient.Patient;
import com.aurevia.tenant.TenantContext;

/**
 * The single SQL boundary for same-tenant duplicate review and consolidation (IAM-04).
 *
 * <p>V9 applies forced RLS, append-only triggers and version folds to every table
 * here. Writes are INSERTs through the tenant-guarded transaction; the pair lock
 * is taken inside that transaction before any association is appended.</p>
 */
@Repository
public class DuplicateRepository {

    /** Business purpose stamped on identity-administration audit events (OPS-02). */
    public static final String PURPOSE = "IDENTITY_ADMIN";
    /** Audit scope for patient identity events (OPS-02). */
    public static final String SCOPE = "PATIENT";

    private static final RowMapper<Patient> PATIENT_MAPPER = DuplicateRepository::mapPatient;
    private static final RowMapper<DuplicateReview> REVIEW_MAPPER = DuplicateRepository::mapReview;
    private static final RowMapper<Consolidation> CONSOLIDATION_MAPPER = DuplicateRepository::mapConsolidation;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public DuplicateRepository(NamedParameterJdbcTemplate jdbc) {
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
     * List same-tenant likely-duplicate pairs still visible as separate charts.
     *
     * @return ordered pairs keyed by the lower patient identifier
     */
    public List<DuplicatePair> likelyPairs() {
        return jdbc.query("""
                SELECT p.id::text AS survivor_id, p.display_name AS survivor_name,
                       q.id::text AS duplicate_id, q.display_name AS duplicate_name
                  FROM public.current_patients() p
                  JOIN public.current_patients() q
                    ON q.organization_id = p.organization_id
                   AND q.normalized_name = p.normalized_name
                   AND q.dob = p.dob
                   AND q.id > p.id
                 WHERE p.dob IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM public.current_chart_associations() a
                                     -- A consolidated alias must leave the worklist
                                     -- entirely: the ordered join can place it on
                                     -- either the p or the q side.
                                     WHERE a.alias_id IN (p.id, q.id) AND a.status = 'ACTIVE')
                  ORDER BY p.normalized_name, p.id, q.id
                """, (resultSet, rowNumber) -> new DuplicatePair(
                resultSet.getString("survivor_id"), resultSet.getString("survivor_name"),
                resultSet.getString("duplicate_id"), resultSet.getString("duplicate_name")));
    }

    /**
     * Find an effective patient by opaque identifier.
     *
     * @param id patient identifier
     * @return the effective patient when visible, otherwise empty
     */
    public Optional<Patient> findPatient(String id) {
        return jdbc.query("""
                SELECT id::text, version, mrn, mrn_issuer, display_name, normalized_name,
                       dob::text, dob_unknown_reason, language, contacts::text,
                       identifiers::text, consents::text
                  FROM public.current_patients() WHERE id = CAST(:id AS uuid)
                """, Map.of("id", id), PATIENT_MAPPER).stream().findFirst();
    }

    /**
     * Find the review ticket for a pair, with status folded from consolidation history.
     *
     * @param survivorId  surviving patient identifier
     * @param duplicateId duplicate patient identifier
     * @return the review when visible, otherwise empty
     */
    public Optional<DuplicateReview> findReviewByPair(String survivorId, String duplicateId) {
        return jdbc.query("""
                SELECT r.id::text, r.survivor_id::text, r.duplicate_id::text, r.opened_by_subject,
                       r.version, coalesce(c.status, 'OPEN') AS status
                  FROM public.duplicate_review r
                  LEFT JOIN public.current_consolidations() c ON c.review_id = r.id
                 WHERE r.survivor_id = CAST(:survivor AS uuid)
                   AND r.duplicate_id = CAST(:duplicate AS uuid)
                """, new MapSqlParameterSource().addValue("survivor", survivorId)
                .addValue("duplicate", duplicateId), REVIEW_MAPPER).stream().findFirst();
    }

    /**
     * Find a review ticket by opaque identifier.
     *
     * @param id review identifier
     * @return the review when visible, otherwise empty
     */
    public Optional<DuplicateReview> findReviewById(String id) {
        return jdbc.query("""
                SELECT r.id::text, r.survivor_id::text, r.duplicate_id::text, r.opened_by_subject,
                       r.version, coalesce(c.status, 'OPEN') AS status
                  FROM public.duplicate_review r
                  LEFT JOIN public.current_consolidations() c ON c.review_id = r.id
                 WHERE r.id = CAST(:id AS uuid)
                """, Map.of("id", id), REVIEW_MAPPER).stream().findFirst();
    }

    /**
     * Read the current consolidation for a review.
     *
     * @param reviewId review identifier
     * @return the latest consolidation event, otherwise empty
     */
    public Optional<Consolidation> findCurrentConsolidation(String reviewId) {
        return currentConsolidation(reviewId);
    }

    private Optional<Consolidation> currentConsolidation(String reviewId) {
        return jdbc.query("""
                SELECT id::text, review_id::text, survivor_id::text, duplicate_id::text, version,
                       status, approver_subject, opened_by_subject, reason, correction_reason
                  FROM public.current_consolidations() WHERE review_id = CAST(:reviewId AS uuid)
                """, Map.of("reviewId", reviewId), CONSOLIDATION_MAPPER).stream().findFirst();
    }

    /**
     * Append a review ticket opened by the authenticated actor.
     *
     * @param organizationId active organization identifier
     * @param survivorId     surviving patient identifier
     * @param duplicateId    duplicate patient identifier
     * @param openedBy       authenticated subject from the tenant context
     * @return number of inserted rows
     */
    public int insertReview(String organizationId, String survivorId, String duplicateId, String openedBy) {
        return jdbc.update("""
                INSERT INTO public.duplicate_review
                    (organization_id, survivor_id, duplicate_id, opened_by_subject, version)
                VALUES (CAST(:organizationId AS uuid), CAST(:survivor AS uuid),
                        CAST(:duplicate AS uuid), :openedBy, 1)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("survivor", survivorId).addValue("duplicate", duplicateId)
                .addValue("openedBy", openedBy));
    }

    /**
     * List dependencies recorded against a patient.
     *
     * @param patientId patient identifier
     * @return dependencies in insertion order
     */
    public List<ReviewDetail.Dependency> dependencies(String patientId) {
        return jdbc.query("""
                SELECT id::text, kind, reference, ambiguous, resolved, created_at
                  FROM (SELECT DISTINCT ON (patient_id, kind, reference)
                               id, kind, reference, ambiguous, resolved, created_at
                          FROM public.consolidation_dependency
                         WHERE patient_id = CAST(:patientId AS uuid)
                         ORDER BY patient_id, kind, reference, created_at DESC, id DESC) latest
                 ORDER BY created_at, id
                """, Map.of("patientId", patientId), (resultSet, rowNumber) -> new ReviewDetail.Dependency(
                resultSet.getString("id"), resultSet.getString("kind"), resultSet.getString("reference"),
                resultSet.getBoolean("ambiguous"), resultSet.getBoolean("resolved")));
    }

    /**
     * Count ambiguous, still-unresolved dependencies on a patient.
     *
     * @param patientId patient identifier
     * @return number of blocking dependencies
     */
    public int hasUnresolvedBlockers(String patientId) {
        // Resolution is appended as a newer row; fold to the latest state per
        // (patient, kind, reference) so a resolved dependency stops blocking.
        Integer count = jdbc.queryForObject("""
                SELECT count(*)::int
                  FROM (SELECT DISTINCT ON (patient_id, kind, reference) ambiguous, resolved
                          FROM public.consolidation_dependency
                         WHERE patient_id = CAST(:patientId AS uuid)
                         ORDER BY patient_id, kind, reference, created_at DESC, id DESC) latest
                 WHERE ambiguous AND NOT resolved
                """, Map.of("patientId", patientId), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Lock both patient records inside the caller's transaction.
     *
     * @param survivorId  surviving patient identifier
     * @param duplicateId duplicate patient identifier
     * @return number of distinct locked patient identifiers
     */
    public int lockPair(String survivorId, String duplicateId) {
        Integer locked = jdbc.queryForObject("""
                SELECT public.lock_patient_pair(CAST(:survivor AS uuid), CAST(:duplicate AS uuid))
                """, new MapSqlParameterSource().addValue("survivor", survivorId)
                .addValue("duplicate", duplicateId), Integer.class);
        return locked == null ? 0 : locked;
    }

    /**
     * Append a versioned chart association.
     *
     * @param id             opaque association identifier generated by the caller
     * @param organizationId active organization identifier
     * @param aliasId        patient whose visibility is moved or restored
     * @param aliasVersion   effective version of that patient at command time
     * @param survivorId     surviving patient identifier
     * @param status         {@code ACTIVE} or {@code REVERSED}
     * @param preservedMrn   original MRN of the alias
     * @param preservedIssuer original MRN issuer of the alias
     * @param preservedIdentifiers original identity JSON of the alias
     * @param preservedConsents original evidence JSON of the alias
     * @param reason         actor-supplied reason
     * @return number of inserted rows
     */
    public int insertAssociation(String id, String organizationId, String aliasId, int aliasVersion,
                                 String survivorId, String status, String preservedMrn, String preservedIssuer,
                                 String preservedIdentifiers, String preservedConsents, String reason) {
        return jdbc.update("""
                INSERT INTO public.chart_association
                    (organization_id, id, alias_id, alias_version, survivor_id, version, status,
                     preserved_mrn, preserved_issuer, preserved_identifiers, preserved_consents, reason)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:alias AS uuid), :aliasVersion,
                        CAST(:survivor AS uuid), public.next_chart_association_version(CAST(:alias AS uuid)),
                        :status, :mrn, :issuer, CAST(:identifiers AS jsonb), CAST(:consents AS jsonb), :reason)
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("organizationId", organizationId)
                .addValue("alias", aliasId).addValue("aliasVersion", aliasVersion)
                .addValue("survivor", survivorId).addValue("status", status)
                .addValue("mrn", preservedMrn).addValue("issuer", preservedIssuer)
                .addValue("identifiers", preservedIdentifiers).addValue("consents", preservedConsents)
                .addValue("reason", reason));
    }

    /**
     * Append a consolidation or correction event.
     *
     * @param id             opaque event identifier generated by the caller
     * @param organizationId active organization identifier
     * @param reviewId       review identifier
     * @param associationId  association appended by this command
     * @param survivorId     surviving patient identifier
     * @param duplicateId    duplicate patient identifier
     * @param status         {@code CONSOLIDATED} or {@code CORRECTED}
     * @param approverSubject authenticated actor performing the command
     * @param openedBySubject original reviewer retained as provenance
     * @param reason         consolidation reason
     * @param correctionReason correction reason, or {@code null}
     * @return number of inserted rows
     */
    public int insertConsolidation(String id, String organizationId, String reviewId, String associationId,
                                   String survivorId, String duplicateId, String status,
                                   String approverSubject, String openedBySubject, String reason,
                                   String correctionReason) {
        return jdbc.update("""
                INSERT INTO public.consolidation
                    (organization_id, id, review_id, association_id, survivor_id, duplicate_id, version,
                     status, approver_subject, opened_by_subject, reason, correction_reason)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:review AS uuid),
                        CAST(:association AS uuid), CAST(:survivor AS uuid), CAST(:duplicate AS uuid),
                        public.next_consolidation_version(CAST(:review AS uuid)),
                        :status, :approver, :openedBy, :reason, :correctionReason)
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("organizationId", organizationId)
                .addValue("review", reviewId).addValue("association", associationId)
                .addValue("survivor", survivorId).addValue("duplicate", duplicateId)
                .addValue("status", status).addValue("approver", approverSubject)
                .addValue("openedBy", openedBySubject).addValue("reason", reason)
                .addValue("correctionReason", correctionReason));
    }

    /**
     * Resolve the patient id a chart currently points at after association folding.
     *
     * @param id patient identifier
     * @return effective patient identifier as text
     */
    public String effectivePatientId(String id) {
        return jdbc.queryForObject("""
                SELECT public.effective_patient_id(CAST(:id AS uuid))::text
                """, Map.of("id", id), String.class);
    }

    /**
     * Create an opaque identifier for an appended association or event.
     *
     * @return a fresh UUID as text
     */
    public String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Append one OPS-02 audit event inside the caller's transaction.
     *
     * @param context    active tenant context
     * @param action     identity action key
     * @param resourceId patient or review identifier addressed by the command
     * @param outcome    success, denied, not-found, conflict or blocked outcome
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

    private static DuplicateReview mapReview(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DuplicateReview(resultSet.getString("id"), resultSet.getString("survivor_id"),
                resultSet.getString("duplicate_id"), resultSet.getString("opened_by_subject"),
                resultSet.getInt("version"), resultSet.getString("status"));
    }

    private static Consolidation mapConsolidation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Consolidation(resultSet.getString("id"), resultSet.getString("review_id"),
                resultSet.getString("survivor_id"), resultSet.getString("duplicate_id"),
                resultSet.getInt("version"), resultSet.getString("status"),
                resultSet.getString("approver_subject"), resultSet.getString("opened_by_subject"),
                resultSet.getString("reason"), resultSet.getString("correction_reason"));
    }

    /**
     * A same-tenant duplicate candidate for the review worklist.
     *
     * @param survivorId   effective patient with the lower identifier
     * @param survivorName display spelling of that patient
     * @param duplicateId  suspected duplicate patient identifier
     * @param duplicateName display spelling of the duplicate
     */
    public record DuplicatePair(String survivorId, String survivorName,
                                String duplicateId, String duplicateName) {
    }
}
