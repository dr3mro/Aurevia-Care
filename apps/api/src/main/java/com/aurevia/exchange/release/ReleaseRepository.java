package com.aurevia.exchange.release;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.tenant.TenantContext;

/**
 * The single SQL boundary for patient release commands.
 *
 * <p>Every statement runs inside the tenant-guarded transaction. V24 applies
 * forced RLS to all three tables, derives authors and actors from {@code app.actor},
 * and makes the event and outbox ledgers append-only for every role.</p>
 */
@Repository
public class ReleaseRepository {

    /** Business purpose stamped on release audit events. */
    public static final String PURPOSE = "RELEASE_ADMIN";
    /** Audit resource scope for release events. */
    public static final String SCOPE = "RELEASE";

    private static final String COLUMNS = """
            id::text AS id, organization_id::text AS organization_id, patient_id::text AS patient_id,
            revision_id, revision_version, issuing_organization_id::text AS issuing_organization_id,
            policy_version, kind, state, effective_from, hold_reason, hold_expires_at,
            reviewed_by::text AS reviewed_by, reviewed_at, supersedes_id::text AS supersedes_id,
            version, created_by::text AS created_by, created_at
            """;

    private static final RowMapper<PatientRelease> RELEASE_MAPPER = ReleaseRepository::mapRelease;
    private static final RowMapper<ReleaseEvent> EVENT_MAPPER = ReleaseRepository::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public ReleaseRepository(NamedParameterJdbcTemplate jdbc) {
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
     * Check the live, verified account-to-chart link for one patient-scoped read.
     *
     * <p>The rule lives once in the V10 {@code public.has_chart_access} fold over the
     * append-only {@code account_chart_link} ledger. Forced RLS and the transaction-local
     * tenant GUC confine the answer to the active organization, and an unlink takes effect
     * on the next request without a token or session refresh. The fold is read inside the
     * caller's guarded transaction, never at connection time.</p>
     *
     * @param subject   authenticated subject from the tenant context
     * @param patientId tenant-local patient identifier addressed by the request
     * @return {@code true} only when the current event is a verified link to this chart
     */
    public boolean hasVerifiedChartLink(String subject, String patientId) {
        Boolean linked = jdbc.queryForObject(
                "SELECT public.has_chart_access(:subject, CAST(:patientId AS uuid))",
                new MapSqlParameterSource().addValue("subject", subject).addValue("patientId", patientId),
                Boolean.class);
        return Boolean.TRUE.equals(linked);
    }

    /**
     * Append one authorization. Author, tenant and timestamp are database-owned.
     *
     * @param organizationId        active organization identifier
     * @param patientId             tenant-local patient identifier
     * @param revisionId            immutable report revision identifier
     * @param revisionVersion       version of the referenced revision
     * @param issuingOrganizationId organization that issued the revision
     * @param policyVersion         approved release policy version applied
     * @param kind                  release policy kind
     * @param state                 initial lifecycle state
     * @param effectiveFrom         instant from which access may open
     * @param holdReason            hold reason, or {@code null}
     * @param holdExpiresAt         hold review deadline, or {@code null}
     * @param supersedesId          release replaced by this one, or {@code null}
     * @return the generated release identifier
     */
    public String insertRelease(String organizationId, String patientId, String revisionId,
                                int revisionVersion, String issuingOrganizationId, int policyVersion,
                                String kind, String state, Instant effectiveFrom, String holdReason,
                                Instant holdExpiresAt, String supersedesId) {
        return jdbc.queryForObject("""
                INSERT INTO public.patient_release
                    (organization_id, patient_id, revision_id, revision_version,
                     issuing_organization_id, policy_version, kind, state, effective_from,
                     hold_reason, hold_expires_at, supersedes_id)
                VALUES (CAST(:organizationId AS uuid), CAST(:patientId AS uuid), :revisionId, :revisionVersion,
                        CAST(:issuingOrganizationId AS uuid), :policyVersion, :kind, :state,
                        CAST(:effectiveFrom AS timestamptz), :holdReason,
                        CAST(:holdExpiresAt AS timestamptz), CAST(:supersedesId AS uuid))
                RETURNING id::text
                """, params(organizationId)
                .addValue("patientId", patientId)
                .addValue("revisionId", revisionId)
                .addValue("revisionVersion", revisionVersion)
                .addValue("issuingOrganizationId", issuingOrganizationId)
                .addValue("policyVersion", policyVersion)
                .addValue("kind", kind)
                .addValue("state", state)
                .addValue("effectiveFrom", effectiveFrom.toString())
                .addValue("holdReason", holdReason)
                .addValue("holdExpiresAt", holdExpiresAt == null ? null : holdExpiresAt.toString())
                .addValue("supersedesId", supersedesId), String.class);
    }

    /**
     * Find one release; forced RLS conceals another tenant's row.
     *
     * @param id tenant-local release identifier
     * @return the release when visible, otherwise empty
     */
    public Optional<PatientRelease> findRelease(String id) {
        return jdbc.query("""
                SELECT
                """ + COLUMNS + """
                  FROM public.patient_release WHERE id = CAST(:id AS uuid)
                """, Map.of("id", id), RELEASE_MAPPER).stream().findFirst();
    }

    /**
     * Find any release for one patient and report revision, including terminal rows.
     *
     * <p>A revision identifier is one-way: once withdrawn or corrected it must never be
     * released again. Callers therefore reject on any existing row, not only on a live one,
     * so a retracted or superseded result cannot be silently re-published.</p>
     *
     * @param patientId  tenant-local patient identifier
     * @param revisionId immutable report revision identifier
     * @return the release when one exists in any state, otherwise empty
     */
    public Optional<PatientRelease> findAnyByPatientAndRevision(String patientId, String revisionId) {
        return jdbc.query("""
                SELECT
                """ + COLUMNS + """
                  FROM public.patient_release
                 WHERE patient_id = CAST(:patientId AS uuid) AND revision_id = :revisionId
                """, new MapSqlParameterSource().addValue("patientId", patientId)
                .addValue("revisionId", revisionId), RELEASE_MAPPER).stream().findFirst();
    }

    /**
     * Lift a held release after review, with a compare-and-set version precondition.
     *
     * <p>The reviewer and review instant are derived from the authenticated actor by
     * the V24 update trigger, never from the request body.</p>
     *
     * @param id      tenant-local release identifier
     * @param version expected current version
     * @return number of rows changed
     */
    public int liftHold(String id, int version) {
        return jdbc.update("""
                UPDATE public.patient_release
                   SET state = 'AUTHORIZED', version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :version AND state = 'HELD'
                """, new MapSqlParameterSource().addValue("id", id).addValue("version", version));
    }

    /**
     * Withdraw a live release with a compare-and-set version precondition.
     *
     * @param id      tenant-local release identifier
     * @param version expected current version
     * @return number of rows changed
     */
    public int withdraw(String id, int version) {
        return jdbc.update("""
                UPDATE public.patient_release
                   SET state = 'WITHDRAWN', version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :version
                   AND state IN ('HELD', 'AUTHORIZED')
                """, new MapSqlParameterSource().addValue("id", id).addValue("version", version));
    }

    /**
     * Mark a release corrected with a compare-and-set version precondition.
     *
     * @param id      tenant-local release identifier
     * @param version expected current version
     * @return number of rows changed
     */
    public int correct(String id, int version) {
        return jdbc.update("""
                UPDATE public.patient_release
                   SET state = 'CORRECTED', version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :version
                   AND state IN ('HELD', 'AUTHORIZED')
                """, new MapSqlParameterSource().addValue("id", id).addValue("version", version));
    }

    /**
     * List authorized releases for a patient; visibility time is folded in the domain.
     *
     * @param patientId tenant-local patient identifier
     * @return all authorized releases visible to this tenant
     */
    public List<PatientRelease> findAuthorizedByPatient(String patientId) {
        return jdbc.query("""
                SELECT
                """ + COLUMNS + """
                  FROM public.patient_release
                 WHERE patient_id = CAST(:patientId AS uuid) AND state = 'AUTHORIZED'
                """, Map.of("patientId", patientId), RELEASE_MAPPER);
    }

    /**
     * Read the append-only decision history of one release.
     *
     * @param releaseId tenant-local release identifier
     * @return events in commit order
     */
    public List<ReleaseEvent> findEvents(String releaseId) {
        return jdbc.query("""
                SELECT id::text AS id, release_id::text AS release_id, action, from_state,
                       to_state, reason, actor_id::text AS actor_id, occurred_at
                  FROM public.release_event
                 WHERE release_id = CAST(:releaseId AS uuid)
                 ORDER BY occurred_at, id
                """, Map.of("releaseId", releaseId), EVENT_MAPPER);
    }

    /**
     * Append one decision event; the actor is forced from the request context.
     *
     * @param context    active tenant context
     * @param releaseId  release the decision describes
     * @param action     decision action key
     * @param fromState  state before the decision, or {@code null}
     * @param toState    state after the decision
     * @param reason     non-sensitive reason, or {@code null}
     */
    public void insertEvent(TenantContext context, String releaseId, String action,
                            String fromState, String toState, String reason) {
        jdbc.update("""
                INSERT INTO public.release_event
                    (organization_id, release_id, action, from_state, to_state, reason)
                VALUES (CAST(:organizationId AS uuid), CAST(:releaseId AS uuid), :action,
                        :fromState, :toState, :reason)
                """, params(context.organizationId())
                .addValue("releaseId", releaseId)
                .addValue("action", action)
                .addValue("fromState", fromState)
                .addValue("toState", toState)
                .addValue("reason", reason));
    }

    /**
     * Append one generic outbox notification for a release decision.
     *
     * @param context        active tenant context
     * @param releaseId      release whose decision is published
     * @param eventType      decision event type
     * @param recipientScope policy-defined recipient class, never patient content
     */
    public void insertNotification(TenantContext context, String releaseId, String eventType,
                                   String recipientScope) {
        jdbc.update("""
                INSERT INTO public.release_notification
                    (organization_id, release_id, event_type, recipient_scope, correlation_id)
                VALUES (CAST(:organizationId AS uuid), CAST(:releaseId AS uuid), :eventType,
                        :recipientScope, :correlationId)
                """, params(context.organizationId())
                .addValue("releaseId", releaseId)
                .addValue("eventType", eventType)
                .addValue("recipientScope", recipientScope)
                .addValue("correlationId", context.correlationId()));
    }

    /**
     * Append one audit event inside the caller's transaction.
     *
     * @param context    active tenant context
     * @param action     release action key
     * @param resourceId release or patient identifier addressed by the command
     * @param outcome    success, denied, not-found, conflict or stale outcome
     */
    public void appendAudit(TenantContext context, String action, String resourceId, String outcome) {
        jdbc.update("""
                INSERT INTO public.audit_event
                    (organization_id, actor_id, action, resource_type, resource_id,
                     purpose, scope, correlation_id, outcome)
                VALUES (CAST(:organizationId AS uuid), NULL, :action, :resourceType, :resourceId,
                        :purpose, :scope, :correlationId, :outcome)
                """, params(context.organizationId())
                .addValue("action", action)
                .addValue("resourceType", SCOPE)
                .addValue("resourceId", resourceId)
                .addValue("purpose", PURPOSE)
                .addValue("scope", SCOPE)
                .addValue("correlationId", context.correlationId())
                .addValue("outcome", outcome));
    }

    private MapSqlParameterSource params(String organizationId) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId);
    }

    private static java.time.Instant instant(ResultSet resultSet, String column) throws SQLException {
        java.time.OffsetDateTime value = resultSet.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static PatientRelease mapRelease(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PatientRelease(
                resultSet.getString("id"),
                resultSet.getString("organization_id"),
                resultSet.getString("patient_id"),
                resultSet.getString("revision_id"),
                resultSet.getInt("revision_version"),
                resultSet.getString("issuing_organization_id"),
                resultSet.getInt("policy_version"),
                ReleaseKind.valueOf(resultSet.getString("kind")),
                ReleaseState.valueOf(resultSet.getString("state")),
                instant(resultSet, "effective_from"),
                resultSet.getString("hold_reason"),
                instant(resultSet, "hold_expires_at"),
                resultSet.getString("reviewed_by"),
                instant(resultSet, "reviewed_at"),
                resultSet.getString("supersedes_id"),
                resultSet.getInt("version"),
                resultSet.getString("created_by"),
                instant(resultSet, "created_at"));
    }

    private static ReleaseEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ReleaseEvent(resultSet.getString("id"), resultSet.getString("release_id"),
                resultSet.getString("action"), resultSet.getString("from_state"),
                resultSet.getString("to_state"), resultSet.getString("reason"),
                resultSet.getString("actor_id"), instant(resultSet, "occurred_at"));
    }
}
