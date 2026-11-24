package com.aurevia.messaging.flows;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.messaging.flows.PatientFlowContract.AppLocale;
import com.aurevia.messaging.flows.PatientFlowContract.AppPlatform;
import com.aurevia.messaging.flows.PatientFlowContract.FlowAudit;
import com.aurevia.messaging.flows.PatientFlowContract.NoticeKind;

/**
 * PostgreSQL-backed persistence boundary for the patient-flow presentation
 * policy (ARC-01, ARC-02, OPS-02).
 *
 * <p>V61 owns the three tenant tables and their forced row-level security. Every
 * operation runs in the tenant-guarded transaction, which binds
 * {@code app.current_org()} and {@code app.actor} after the transaction opens;
 * forced RLS therefore confines each statement to the active organization and
 * no manual organization predicate is needed on reads. The {@code organizationId}
 * argument identifies that same active tenant and is written explicitly on
 * inserts so the policy's {@code WITH CHECK} rejects a mismatch.</p>
 *
 * <p>Membership is owned by the tenancy and staff modules. This adapter reads
 * the authoritative {@code public.has_active_membership} fold rather than
 * projecting a second copy, so a deactivation takes effect on the next request.
 * Policy versions and approved notices are approved configuration populated by
 * an authorized administration path; the access-fact ledger is append-only.</p>
 */
@Repository
public class JdbcFlowPolicyStore implements FlowPolicyStore {

    private static final RowMapper<FlowAudit> AUDIT_MAPPER = JdbcFlowPolicyStore::mapAudit;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the PostgreSQL flow-policy store.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcFlowPolicyStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        // organizationId is intentionally unused: the guarded transaction binds
        // the active tenant, and the fold is tenant-scoped by RLS. Passing the
        // subject alone prevents a member of another tenant from being matched.
        return transactions.execute(status -> {
            Boolean active = jdbc.queryForObject("SELECT public.has_active_membership(:subject)",
                    new MapSqlParameterSource().addValue("subject", subject), Boolean.class);
            return Boolean.TRUE.equals(active);
        });
    }

    @Override
    public Integer policyVersion(String organizationId, AppLocale locale, AppPlatform platform) {
        // MAX returns one row even when no policy exists; its SQL NULL maps to a
        // null Integer, matching the interface's "no published version" contract
        // without an exception path.
        return transactions.execute(status -> jdbc.queryForObject("""
                SELECT MAX(version) FROM public.flow_policy
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND locale = :locale AND platform = :platform
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("locale", locale.wire()).addValue("platform", platform.wire()),
                Integer.class));
    }

    @Override
    public List<NoticeKind> approvedNotices(String organizationId) {
        return transactions.execute(status -> jdbc.queryForList("""
                SELECT notice_kind FROM public.flow_approved_notice
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY notice_kind
                """, new MapSqlParameterSource().addValue("organizationId", organizationId),
                String.class).stream().map(NoticeKind::from).toList());
    }

    @Override
    public void appendAudit(FlowAudit audit) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.flow_access_audit
                    (organization_id, subject, purpose, action, correlation_id, outcome, occurred_at)
                VALUES (CAST(:organizationId AS uuid), :subject, :purpose, :action,
                        :correlationId, :outcome, CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", audit.organizationId())
                        .addValue("subject", audit.subject())
                        .addValue("purpose", audit.purpose())
                        .addValue("action", audit.action())
                        .addValue("correlationId", audit.correlationId())
                        .addValue("outcome", audit.outcome())
                        .addValue("occurredAt", dateTime(audit.occurredAt()))));
    }

    @Override
    public List<FlowAudit> auditEvents(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, subject, purpose, action, correlation_id,
                       outcome, occurred_at FROM public.flow_access_audit ORDER BY seq
                """, AUDIT_MAPPER));
    }

    private static FlowAudit mapAudit(ResultSet resultSet, int rowNumber) throws SQLException {
        return new FlowAudit(resultSet.getString("organization_id"),
                resultSet.getString("subject"), resultSet.getString("purpose"),
                resultSet.getString("action"), resultSet.getString("correlation_id"),
                resultSet.getString("outcome"), instant(resultSet, "occurred_at"));
    }

    /**
     * Convert a server instant to the driver's timestamptz representation.
     *
     * <p>pgjdbc cannot infer a SQL type for {@link java.time.Instant}; the UTC
     * offset keeps the wire value unambiguous.</p>
     *
     * @param instant server instant
     * @return an offset date time
     */
    private static OffsetDateTime dateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Read a non-null timestamptz column as a server instant.
     *
     * @param resultSet result row
     * @param column    column label
     * @return the instant
     * @throws SQLException when the column cannot be read
     */
    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
