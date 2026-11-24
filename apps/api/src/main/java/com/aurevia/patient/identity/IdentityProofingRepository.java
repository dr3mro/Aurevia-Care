package com.aurevia.patient.identity;

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
 * The single SQL boundary for patient identity proofing (IAM-05).
 *
 * <p>V10 applies forced row-level security and append-only triggers to the
 * identity tables. Every statement runs inside the tenant-guarded transaction,
 * so the active organization is bound from authentication before any SQL.</p>
 */
@Repository
public class IdentityProofingRepository {

    /** Business purpose stamped on identity audit events (OPS-02). */
    public static final String PURPOSE = "PATIENT_IDENTITY";
    /** Audit scope for identity events (OPS-02). */
    public static final String SCOPE = "PATIENT_IDENTITY";

    private static final RowMapper<PatientAccount> ACCOUNT_MAPPER = IdentityProofingRepository::mapAccount;
    private static final RowMapper<InvitationRecord> INVITATION_MAPPER = IdentityProofingRepository::mapInvitation;
    private static final RowMapper<ChartLink> LINK_MAPPER = IdentityProofingRepository::mapLink;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public IdentityProofingRepository(NamedParameterJdbcTemplate jdbc) {
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
     * Check whether a verified account already exists for a subject in this tenant.
     *
     * @param subject login subject
     * @return {@code true} when the account exists
     */
    public boolean accountExists(String subject) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM public.patient_account WHERE subject = :subject)",
                Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Check whether a patient chart exists in this tenant.
     *
     * @param chartId opaque patient identifier
     * @return {@code true} when the chart is visible
     */
    public boolean chartExists(String chartId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.current_patients() WHERE id = CAST(:chartId AS uuid))
                """, Map.of("chartId", chartId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Insert a site-verified patient account.
     *
     * @param organizationId active organization identifier
     * @param id             opaque account identifier
     * @param subject        verified login subject
     * @param displayName    account label
     * @return number of inserted rows
     */
    public int insertAccount(String organizationId, String id, String subject, String displayName) {
        return jdbc.update("""
                INSERT INTO public.patient_account (organization_id, id, subject, display_name)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :subject, :displayName)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("id", id).addValue("subject", subject).addValue("displayName", displayName));
    }

    /**
     * Find a verified account by subject within the active tenant.
     *
     * @param subject login subject
     * @return the account when visible, otherwise empty
     */
    public Optional<PatientAccount> findAccount(String subject) {
        return jdbc.query("""
                SELECT id::text, subject, display_name FROM public.patient_account WHERE subject = :subject
                """, Map.of("subject", subject), ACCOUNT_MAPPER).stream().findFirst();
    }

    /**
     * Insert a single-use invitation; the token hash is stored, never the token.
     *
     * @param organizationId active organization identifier
     * @param id             opaque invitation identifier
     * @param subject        account the invitation is bound to
     * @param chartId        chart whose link it authorizes
     * @param purpose        bounded purpose key
     * @param token          one-time raw token; the database stores only its SHA-256 hash
     * @param ttlSeconds     lifetime in seconds
     * @return number of inserted rows
     */
    public int insertInvitation(String organizationId, String id, String subject, String chartId,
                                String purpose, String token, long ttlSeconds) {
        return jdbc.update("""
                INSERT INTO public.account_invitation
                    (organization_id, id, account_id, chart_id, token_hash, purpose, expires_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid),
                        (SELECT id FROM public.patient_account WHERE subject = :subject),
                        CAST(:chartId AS uuid),
                        encode(sha256(convert_to(:token, 'UTF8')), 'hex'), :purpose,
                        now() + CAST(:ttlSeconds AS bigint) * interval '1 second')
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("id", id).addValue("subject", subject).addValue("chartId", chartId)
                .addValue("token", token).addValue("purpose", purpose).addValue("ttlSeconds", ttlSeconds));
    }

    /**
     * Find an invitation by its raw token; the match is made on the stored hash.
     *
     * @param token one-time raw token
     * @return the invitation when visible, otherwise empty
     */
    public Optional<InvitationRecord> findInvitation(String token) {
        return jdbc.query("""
                SELECT i.id::text, a.subject, i.chart_id::text, i.purpose,
                       i.expires_at::text, (i.used_at IS NOT NULL) AS consumed
                  FROM public.account_invitation i
                  JOIN public.patient_account a
                    ON a.organization_id = i.organization_id AND a.id = i.account_id
                 WHERE i.token_hash = encode(sha256(convert_to(:token, 'UTF8')), 'hex')
                """, Map.of("token", token), INVITATION_MAPPER).stream().findFirst();
    }

    /**
     * Atomically consume an invitation if it is unused and unexpired.
     *
     * @param id opaque invitation identifier
     * @return {@code true} when this call consumed the invitation
     */
    public boolean consumeInvitation(String id) {
        Boolean consumed = jdbc.queryForObject(
                "SELECT public.consume_invitation(CAST(:id AS uuid))", Map.of("id", id), Boolean.class);
        return Boolean.TRUE.equals(consumed);
    }

    /**
     * Invalidate every unconsumed invitation for one account and chart.
     *
     * <p>An unlink or re-verification must also retire invitations that were
     * issued for the same relationship but never redeemed; otherwise a stale
     * token could silently restore access after a dispute. The update is
     * scoped by subject and chart inside the tenant-guarded transaction, and
     * the database clock supplies the consumption time. The runtime role may
     * update invitations (V10), so no migration change is required.</p>
     *
     * @param subject account subject owning the invitations
     * @param chartId patient chart identifier
     * @return number of invitations invalidated
     */
    public int consumeOutstandingInvitations(String subject, String chartId) {
        return jdbc.update("""
                UPDATE public.account_invitation
                   SET used_at = now()
                 WHERE used_at IS NULL
                   AND account_id = (SELECT id FROM public.patient_account WHERE subject = :subject)
                   AND chart_id = CAST(:chartId AS uuid)
                """, new MapSqlParameterSource().addValue("subject", subject).addValue("chartId", chartId));
    }

    /**
     * Append one account-to-chart link event.
     *
     * @param organizationId active organization identifier
     * @param id             opaque event identifier
     * @param subject        account subject owning the link
     * @param chartId        patient chart identifier
     * @param event          {@code linked} or {@code unlinked}
     * @param method         provenance of the event
     * @param reason         optional reason, or {@code null}
     * @return number of inserted rows
     */
    public int insertLink(String organizationId, String id, String subject, String chartId,
                          String event, String method, String reason) {
        return jdbc.update("""
                INSERT INTO public.account_chart_link
                    (organization_id, id, account_id, chart_id, event, method, reason)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid),
                        (SELECT id FROM public.patient_account WHERE subject = :subject),
                        CAST(:chartId AS uuid), :event, :method, :reason)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("id", id).addValue("subject", subject).addValue("chartId", chartId)
                .addValue("event", event).addValue("method", method).addValue("reason", reason));
    }

    /**
     * Read the current (latest) link event for an account and chart.
     *
     * @param subject account subject
     * @param chartId patient chart identifier
     * @return the current event when one exists, otherwise empty
     */
    public Optional<ChartLink> currentLink(String subject, String chartId) {
        // The fold exposes account_id and seq, not the event id: join the
        // append-only table on the fold's database-owned seq to recover it.
        return jdbc.query("""
                SELECT l.id::text AS id, c.account_subject, c.chart_id::text,
                       c.event, c.method, c.reason, c.occurred_at::text
                  FROM public.current_chart_links() c
                  JOIN public.account_chart_link l
                    ON l.organization_id = c.organization_id
                   AND l.account_id = c.account_id
                   AND l.chart_id = c.chart_id
                   AND l.seq = c.seq
                 WHERE c.account_subject = :subject AND c.chart_id = CAST(:chartId AS uuid)
                """, new MapSqlParameterSource().addValue("subject", subject).addValue("chartId", chartId),
                LINK_MAPPER).stream().findFirst();
    }

    /**
     * Check whether an account currently has an active link to a chart.
     *
     * @param subject account subject
     * @param chartId patient chart identifier
     * @return {@code true} when the current event is a verified link
     */
    public boolean hasAccess(String subject, String chartId) {
        Boolean access = jdbc.queryForObject(
                "SELECT public.has_chart_access(:subject, CAST(:chartId AS uuid))",
                new MapSqlParameterSource().addValue("subject", subject).addValue("chartId", chartId),
                Boolean.class);
        return Boolean.TRUE.equals(access);
    }

    /**
     * List one current link row per chart for an account.
     *
     * @param subject account subject
     * @return current link events ordered by chart
     */
    public List<ChartLink> links(String subject) {
        // Same projection contract as currentLink: the event id comes from the
        // append-only table, never from the current-link fold.
        return jdbc.query("""
                SELECT l.id::text AS id, c.account_subject, c.chart_id::text,
                       c.event, c.method, c.reason, c.occurred_at::text
                  FROM public.current_chart_links() c
                  JOIN public.account_chart_link l
                    ON l.organization_id = c.organization_id
                   AND l.account_id = c.account_id
                   AND l.chart_id = c.chart_id
                   AND l.seq = c.seq
                 WHERE c.account_subject = :subject
                 ORDER BY c.chart_id
                """, Map.of("subject", subject), LINK_MAPPER);
    }

    /**
     * Append one OPS-02 audit event inside the caller's transaction.
     *
     * @param context    active tenant context
     * @param action     identity action key
     * @param resourceId chart or account identifier addressed by the command
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

    private static PatientAccount mapAccount(ResultSet rs, int row) throws SQLException {
        return new PatientAccount(rs.getString("id"), rs.getString("subject"), rs.getString("display_name"));
    }

    private static InvitationRecord mapInvitation(ResultSet rs, int row) throws SQLException {
        return new InvitationRecord(rs.getString("id"), rs.getString("subject"), rs.getString("chart_id"),
                rs.getString("purpose"), rs.getString("expires_at"), rs.getBoolean("consumed"));
    }

    private static ChartLink mapLink(ResultSet rs, int row) throws SQLException {
        return new ChartLink(rs.getString("id"), rs.getString("account_subject"), rs.getString("chart_id"),
                rs.getString("event"), rs.getString("method"), rs.getString("reason"),
                rs.getString("occurred_at"));
    }
}
