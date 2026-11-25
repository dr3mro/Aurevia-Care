package com.aurevia.api.authorization;

import java.sql.Timestamp;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.api.authorization.CommandAuthorization.Decision;

/**
 * Durable OPS-02 decision ledger backed by PostgreSQL.
 *
 * <p>Each call appends one bounded authorization outcome to
 * {@code public.authorization_decision}. The organization comes from the
 * server-derived decision, never from a client field, and every statement must run
 * inside the tenant-guarded transaction that binds {@code app.current_org}; forced
 * row-level security then confines the row to the active tenant. Only codes and
 * identifiers are stored: no request body, clinical text or patient field reaches
 * the ledger. A write failure propagates to the caller so the command fails closed
 * instead of being allowed without evidence.</p>
 */
public final class JdbcDecisionSink implements CommandAuthorization.DecisionSink {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the durable sink.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public JdbcDecisionSink(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void record(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        String organizationId = decision.organizationId();
        // An unauthenticated decision has no tenant to own its row. Refuse it
        // explicitly rather than letting a UUID cast error hide the real cause.
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("authorization decision requires an active organization");
        }
        jdbc.update("""
                INSERT INTO public.authorization_decision
                    (organization_id, subject, action, resource_type, decision,
                     reason_code, correlation_id, decided_at)
                VALUES (CAST(:organizationId AS uuid), :subject, :action, :resourceType,
                        :decision, :reasonCode, :correlationId, :decidedAt)
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("subject", decision.subject())
                .addValue("action", decision.action())
                .addValue("resourceType", decision.commandId())
                .addValue("decision", decision.allowed() ? "allow" : "deny")
                .addValue("reasonCode", decision.reasonCode())
                .addValue("correlationId", decision.correlationId())
                .addValue("decidedAt", Timestamp.from(decision.occurredAt())));
    }
}
