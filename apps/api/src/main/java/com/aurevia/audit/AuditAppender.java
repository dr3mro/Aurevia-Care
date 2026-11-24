package com.aurevia.audit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.aurevia.tenant.TenantContext;

/**
 * Appends one OPS-02 audit event inside the caller's transaction.
 *
 * <p>The actor is never caller-supplied: the V3 trigger derives it from the
 * transaction-local {@code app.actor} binding, so a caller cannot attribute an
 * action to another user. The append is transactional with the command, and a
 * denied command is written before the failure is returned so the denial
 * survives.</p>
 */
@Component
public class AuditAppender {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the appender.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public AuditAppender(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Append one audit event.
     *
     * @param context      active tenant context
     * @param purpose      business purpose of the action, for example {@code SCHEDULING_ADMIN}
     * @param action       audit action key
     * @param resourceType audited resource type
     * @param scope        audit scope
     * @param resourceId   audited resource identifier
     * @param outcome      {@code SUCCESS}, {@code DENIED}, {@code NOT_FOUND} or {@code CONFLICT}
     */
    public void append(TenantContext context, String purpose, String action, String resourceType,
                       String scope, String resourceId, String outcome) {
        jdbc.update("""
                INSERT INTO public.audit_event
                    (organization_id, actor_id, action, resource_type, resource_id,
                     purpose, scope, correlation_id, outcome)
                VALUES (CAST(:organizationId AS uuid), NULL, :action, :resourceType, :resourceId,
                        :purpose, :scope, :correlationId, :outcome)
                """, new MapSqlParameterSource()
                .addValue("organizationId", context.organizationId())
                .addValue("action", action)
                .addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId)
                .addValue("purpose", purpose)
                .addValue("scope", scope)
                .addValue("correlationId", context.correlationId())
                .addValue("outcome", outcome));
    }
}
