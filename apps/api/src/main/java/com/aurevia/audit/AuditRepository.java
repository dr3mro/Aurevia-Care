package com.aurevia.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.tenant.TenantContext;

/**
 * The single SQL boundary for the tenant audit trail.
 *
 * <p>Every statement runs inside the tenant-guarded transaction. Forced RLS from
 * V2 confines the query to the active organization, and the V3 trigger appends
 * each read as its own tamper-evident event.</p>
 */
@Repository
public class AuditRepository {

    /** Business purpose stamped on audit-review reads (OPS-02). */
    public static final String PURPOSE = "AUDIT_REVIEW";
    /** Audit scope for audit-review events (OPS-02). */
    public static final String SCOPE = "AUDIT";

    private static final RowMapper<AuditEvent> EVENT_MAPPER = AuditRepository::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public AuditRepository(NamedParameterJdbcTemplate jdbc) {
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
     * Query one page of audit events, optionally filtered and cursor-paged.
     *
     * @param cursor       exclusive upper bound on seq, or {@code null} for the newest page
     * @param limit        maximum rows to return (the caller fetches one extra for the next cursor)
     * @param action       optional exact action filter
     * @param outcome      optional exact outcome filter
     * @param resourceType optional exact resource-type filter
     * @param from         optional inclusive lower time bound
     * @param to           optional inclusive upper time bound
     * @return matching events in descending seq order
     */
    public List<AuditEvent> query(Long cursor, int limit, String action, String outcome,
                                  String resourceType, String from, String to) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        StringBuilder sql = new StringBuilder("""
                SELECT id::text, occurred_at::text, actor_id::text, action, resource_type,
                       resource_id, purpose, scope, correlation_id, outcome, seq
                  FROM public.audit_event
                 WHERE 1=1
                """);
        if (cursor != null) {
            sql.append(" AND seq < :cursor");
            params.addValue("cursor", cursor);
        }
        appendFilter(sql, params, "action", action);
        appendFilter(sql, params, "outcome", outcome);
        appendFilter(sql, params, "resource_type", resourceType);
        if (from != null) {
            sql.append(" AND occurred_at >= CAST(:from AS timestamptz)");
            params.addValue("from", from);
        }
        if (to != null) {
            sql.append(" AND occurred_at <= CAST(:to AS timestamptz)");
            params.addValue("to", to);
        }
        sql.append(" ORDER BY seq DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, EVENT_MAPPER);
    }

    /**
     * Append one OPS-02 audit event inside the caller's transaction.
     *
     * @param context    active tenant context
     * @param action     audit action key
     * @param resourceId audited resource identifier
     * @param outcome    success or denial outcome
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

    private void appendFilter(StringBuilder sql, MapSqlParameterSource params, String column, String value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = :").append(column);
            params.addValue(column, value);
        }
    }

    private static AuditEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuditEvent(resultSet.getString("id"), resultSet.getString("occurred_at"),
                resultSet.getString("actor_id"), resultSet.getString("action"),
                resultSet.getString("resource_type"), resultSet.getString("resource_id"),
                resultSet.getString("purpose"), resultSet.getString("scope"),
                resultSet.getString("correlation_id"), resultSet.getString("outcome"),
                resultSet.getLong("seq"));
    }
}
