package com.aurevia.smartclinic.g0.s_g0_08;

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

import com.aurevia.smartclinic.g0.s_g0_08.AccessContracts.GrantRow;
import com.aurevia.smartclinic.g0.s_g0_08.AccessContracts.IncidentRow;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Single SQL boundary for the IAM-06 access-control commands.
 *
 * <p>Every statement runs inside the tenant-guarded transaction. V11 enforces forced
 * row-level security on both new tables and a composite facility foreign key, so a
 * caller can never reference a facility outside the active organization.</p>
 */
@Repository
public class AccessRepository {

    private static final RowMapper<GrantRow> GRANT_MAPPER = AccessRepository::mapGrant;
    private static final RowMapper<IncidentRow> INCIDENT_MAPPER = AccessRepository::mapIncident;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborator")
    public AccessRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Re-read active membership from the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the membership is currently active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * Re-read the currently effective role keys from the database fold.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Read the approved configuration value effective now, if any.
     *
     * @param key tenant-local configuration key
     * @return the value, or {@code null} when no approved entry is effective
     */
    public String effectiveConfig(String key) {
        List<String> values = jdbc.queryForList(
                "SELECT value FROM public.effective_config(:key)", Map.of("key", key), String.class);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * Check that a facility is visible in the active organization.
     *
     * @param code tenant-local facility code
     * @return {@code true} when the facility belongs to the active organization
     */
    public boolean facilityExists(String code) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM public.facility WHERE code = :code)",
                Map.of("code", code), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Append one emergency grant row; the organization is bound by forced RLS.
     *
     * @param organizationId active organization identifier
     * @param subject        qualified clinician subject
     * @param facilityCode   facility the grant is scoped to
     * @param purpose        business purpose
     * @param reason         human justification
     * @param roleKey        qualifying role key
     * @param grantedAt      grant start instant
     * @param expiresAt      grant expiry instant
     * @return the persisted grant row
     */
    public GrantRow insertGrant(String organizationId, String subject, String facilityCode, String purpose,
                                String reason, String roleKey, Instant grantedAt, Instant expiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO public.emergency_access_grant
                    (organization_id, subject, facility_code, purpose, reason, role_key,
                     granted_at, expires_at)
                VALUES (CAST(:organizationId AS uuid), :subject, :facilityCode, :purpose, :reason,
                        :roleKey, CAST(:grantedAt AS timestamptz), CAST(:expiresAt AS timestamptz))
                RETURNING id, subject, facility_code, purpose, reason, role_key, granted_at,
                          expires_at, revoked_at, reviewed_by, reviewed_at, review_note
                """, params(organizationId)
                .addValue("subject", subject)
                .addValue("facilityCode", facilityCode)
                .addValue("purpose", purpose)
                .addValue("reason", reason)
                .addValue("roleKey", roleKey)
                .addValue("grantedAt", grantedAt.toString())
                .addValue("expiresAt", expiresAt.toString()), GRANT_MAPPER);
    }

    /**
     * Read the most recent grant for a subject, regardless of its current state.
     *
     * @param subject clinician subject
     * @return the newest grant, or empty when the subject has none
     */
    public Optional<GrantRow> latestGrant(String subject) {
        return jdbc.query("""
                SELECT id, subject, facility_code, purpose, reason, role_key, granted_at,
                       expires_at, revoked_at, reviewed_by, reviewed_at, review_note
                  FROM public.emergency_access_grant
                 WHERE subject = :subject
                 ORDER BY granted_at DESC, id DESC
                 LIMIT 1
                """, Map.of("subject", subject), GRANT_MAPPER).stream().findFirst();
    }

    /**
     * Read one grant by its opaque identifier.
     *
     * @param id grant identifier as text
     * @return the grant, or empty when it is not visible in the active organization
     */
    public Optional<GrantRow> findGrant(String id) {
        return jdbc.query("""
                SELECT id, subject, facility_code, purpose, reason, role_key, granted_at,
                       expires_at, revoked_at, reviewed_by, reviewed_at, review_note
                  FROM public.emergency_access_grant
                 WHERE id::text = :id
                """, Map.of("id", id), GRANT_MAPPER).stream().findFirst();
    }

    /**
     * End an unreviewed, unrevoked grant early with a recorded reason.
     *
     * @param id     grant identifier as text
     * @param reason recorded reason
     * @param now    revocation instant
     * @return number of updated rows; zero means concealed, revoked or unknown
     */
    public int revokeGrant(String id, String reason, Instant now) {
        return jdbc.update("""
                UPDATE public.emergency_access_grant
                   SET revoked_at = CAST(:now AS timestamptz), revoke_reason = :reason
                 WHERE id::text = :id AND revoked_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("reason", reason)
                .addValue("now", now.toString()));
    }

    /**
     * Record an independent review; a grantee cannot review their own grant.
     *
     * @param id       grant identifier as text
     * @param reviewer reviewer subject
     * @param note     reviewer finding
     * @param now      review instant
     * @return number of updated rows; zero means concealed, reviewed or self-review
     */
    public int reviewGrant(String id, String reviewer, String note, Instant now) {
        return jdbc.update("""
                UPDATE public.emergency_access_grant
                   SET reviewed_by = :reviewer, reviewed_at = CAST(:now AS timestamptz),
                       review_note = :note
                 WHERE id::text = :id AND reviewed_at IS NULL AND subject <> :reviewer
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("reviewer", reviewer)
                .addValue("note", note)
                .addValue("now", now.toString()));
    }

    /**
     * List all visible grants with unreviewed ones first.
     *
     * @return grants ordered by review state and start time
     */
    public List<GrantRow> listGrants() {
        return jdbc.query("""
                SELECT id, subject, facility_code, purpose, reason, role_key, granted_at,
                       expires_at, revoked_at, reviewed_by, reviewed_at, review_note
                  FROM public.emergency_access_grant
                 ORDER BY (reviewed_at IS NULL) DESC, granted_at DESC
                """, GRANT_MAPPER);
    }

    /**
     * Append one pending platform support incident.
     *
     * @param organizationId active organization identifier
     * @param reference      tenant-local incident reference
     * @param subject        support subject bound to the incident
     * @param reason         human justification
     * @param createdAt      creation instant
     * @param expiresAt      approval-window expiry instant
     * @return the persisted incident row
     */
    public IncidentRow insertIncident(String organizationId, String reference, String subject, String reason,
                                      Instant createdAt, Instant expiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO public.support_access_incident
                    (organization_id, reference, subject, reason, created_at, expires_at)
                VALUES (CAST(:organizationId AS uuid), :reference, :subject, :reason,
                        CAST(:createdAt AS timestamptz), CAST(:expiresAt AS timestamptz))
                RETURNING id, reference, subject, reason, status, approved_by, approved_at,
                          created_at, expires_at
                """, params(organizationId)
                .addValue("reference", reference)
                .addValue("subject", subject)
                .addValue("reason", reason)
                .addValue("createdAt", createdAt.toString())
                .addValue("expiresAt", expiresAt.toString()), INCIDENT_MAPPER);
    }

    /**
     * Approve a pending incident; the database forbids self-approval.
     *
     * @param reference tenant-local incident reference
     * @param approver  approver subject
     * @param now       approval instant
     * @return number of updated rows; zero means concealed, used or expired
     */
    public int approveIncident(String reference, String approver, Instant now) {
        return jdbc.update("""
                UPDATE public.support_access_incident
                   SET status = 'approved', approved_by = :approver,
                       approved_at = CAST(:now AS timestamptz)
                 WHERE reference = :reference AND status = 'pending'
                   AND expires_at > CAST(:now AS timestamptz)
                   AND subject <> :approver
                """, new MapSqlParameterSource()
                .addValue("reference", reference)
                .addValue("approver", approver)
                .addValue("now", now.toString()));
    }

    /**
     * Find an approved, unexpired incident for the exact requesting subject.
     *
     * @param reference tenant-local incident reference
     * @param subject   requesting support subject
     * @param now       instant of the access check
     * @return the usable incident, or empty when support access stays denied
     */
    public Optional<IncidentRow> activeIncident(String reference, String subject, Instant now) {
        return jdbc.query("""
                SELECT id, reference, subject, reason, status, approved_by, approved_at,
                       created_at, expires_at
                  FROM public.support_access_incident
                 WHERE reference = :reference AND subject = :subject AND status = 'approved'
                   AND approved_by IS NOT NULL AND approved_at IS NOT NULL
                   AND approved_by <> :subject
                   AND expires_at > CAST(:now AS timestamptz)
                """, new MapSqlParameterSource()
                .addValue("reference", reference)
                .addValue("subject", subject)
                .addValue("now", now.toString()), INCIDENT_MAPPER).stream().findFirst();
    }

    /**
     * Read one incident by its tenant-local reference.
     *
     * @param reference tenant-local incident reference
     * @return the incident, or empty when it is not visible in the active organization
     */
    public Optional<IncidentRow> findIncident(String reference) {
        return jdbc.query("""
                SELECT id, reference, subject, reason, status, approved_by, approved_at,
                       created_at, expires_at
                  FROM public.support_access_incident
                 WHERE reference = :reference
                """, Map.of("reference", reference), INCIDENT_MAPPER).stream().findFirst();
    }

    /**
     * Append one OPS-02 audit event inside the caller's transaction; the actor is
     * derived from the tenant-local GUC, never from the command payload.
     *
     * @param context      active tenant context
     * @param purpose      business purpose of the sensitive action
     * @param action       stable audit action key
     * @param scope        bounded {@code role:resource} scope
     * @param resourceType audited resource type
     * @param resourceId   audited resource identifier
     * @param outcome      {@code SUCCESS}, {@code DENIED}, {@code NOT_FOUND} or {@code CONFLICT}
     */
    public void appendAudit(TenantContext context, String purpose, String action, String scope,
                            String resourceType, String resourceId, String outcome) {
        jdbc.update("""
                INSERT INTO public.audit_event
                    (organization_id, actor_id, action, resource_type, resource_id,
                     purpose, scope, correlation_id, outcome)
                VALUES (CAST(:organizationId AS uuid), NULL, :action, :resourceType, :resourceId,
                        :purpose, :scope, :correlationId, :outcome)
                """, params(context.organizationId())
                .addValue("action", action)
                .addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId)
                .addValue("purpose", purpose)
                .addValue("scope", scope)
                .addValue("correlationId", context.correlationId())
                .addValue("outcome", outcome));
    }

    private MapSqlParameterSource params(String organizationId) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId);
    }

    private static GrantRow mapGrant(ResultSet resultSet, int rowNumber) throws SQLException {
        return new GrantRow(resultSet.getString("id"), resultSet.getString("subject"),
                resultSet.getString("facility_code"), resultSet.getString("purpose"),
                resultSet.getString("reason"), resultSet.getString("role_key"),
                resultSet.getObject("granted_at", Instant.class),
                resultSet.getObject("expires_at", Instant.class),
                resultSet.getObject("revoked_at", Instant.class),
                resultSet.getString("reviewed_by"),
                resultSet.getObject("reviewed_at", Instant.class),
                resultSet.getString("review_note"));
    }

    private static IncidentRow mapIncident(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IncidentRow(resultSet.getString("id"), resultSet.getString("reference"),
                resultSet.getString("subject"), resultSet.getString("reason"),
                resultSet.getString("status"), resultSet.getString("approved_by"),
                resultSet.getObject("approved_at", Instant.class),
                resultSet.getObject("created_at", Instant.class),
                resultSet.getObject("expires_at", Instant.class));
    }
}
