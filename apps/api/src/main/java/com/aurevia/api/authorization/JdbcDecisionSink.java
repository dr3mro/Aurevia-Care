package com.aurevia.api.authorization;

import java.sql.Timestamp;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization.Decision;

/**
 * Durable OPS-02 authorization decision ledger backed by PostgreSQL (V65).
 *
 * <p>Each decision is appended inside the tenant-guarded transaction, so the
 * session's {@code app.current_org} matches the row's organization and forced
 * RLS confines every write and read. A decision with no server identity is
 * stored with a NULL organization as tenant-less evidence; it carries no
 * patient field or request body. If the ledger cannot be written the sink
 * fails closed with {@code AUDIT_UNAVAILABLE}, so a missing audit record can
 * never turn a command into an allowed one.</p>
 */
public final class JdbcDecisionSink implements CommandAuthorization.DecisionSink {

    /** Bounded resource category; the command identifier has its own column. */
    static final String RESOURCE_TYPE = "command";

    private static final String INSERT = """
            INSERT INTO public.authorization_decision
                (organization_id, command_id, subject, action, resource_type,
                 decision, reason_code, correlation_id, decided_at)
            VALUES
                (CAST(:organizationId AS uuid), :commandId, :subject, :action, :resourceType,
                 :decision, :reasonCode, :correlationId, CAST(:decidedAt AS timestamptz))
            """;

    /** Narrow append boundary; the framework template is never exposed by this sink. */
    @FunctionalInterface
    private interface RowAppender {
        void append(MapSqlParameterSource values);
    }

    /** Narrow transaction boundary that binds the tenant before the append. */
    @FunctionalInterface
    private interface TenantTransaction {
        void run(TransactionCallback<Void> work);
    }

    private final RowAppender appender;
    private final TenantTransaction transactions;

    /**
     * Create the durable sink.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template that binds the
     *                     session tenant before the insert
     */
    public JdbcDecisionSink(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        // The injected templates are mutable Spring collaborators. Capturing only
        // their append/run operations keeps this immutable sink from exposing
        // them as its own state while preserving constructor injection.
        this.appender = values -> jdbc.update(INSERT, values);
        this.transactions = work -> transactions.execute(work);
    }

    /**
     * Append one allow or deny decision inside the caller's tenant transaction.
     *
     * @param decision outcome from the authorization gate
     * @throws ApiException {@code 500 AUDIT_UNAVAILABLE} when the ledger write
     *         fails; the command is denied because no evidence was persisted
     */
    @Override
    public void record(Decision decision) {
        try {
            transactions.run(status -> {
                appender.append(new MapSqlParameterSource()
                        .addValue("organizationId", organization(decision.organizationId()))
                        .addValue("commandId", decision.commandId())
                        .addValue("subject", decision.subject())
                        .addValue("action", decision.action())
                        .addValue("resourceType", RESOURCE_TYPE)
                        .addValue("decision", decision.allowed() ? "allow" : "deny")
                        .addValue("reasonCode", decision.reasonCode())
                        .addValue("correlationId", decision.correlationId())
                        .addValue("decidedAt", Timestamp.from(decision.occurredAt())));
                return null;
            });
        } catch (RuntimeException failure) {
            // The transaction guard also rejects a missing tenant context, so a
            // failed write of any kind must be reported as an unavailable audit.
            throw new ApiException(500, "AUDIT_UNAVAILABLE", "authorization decision ledger unavailable");
        }
    }

    /**
     * Map an absent server organization to the tenant-less evidence row.
     *
     * @param organizationId server-derived organization or an empty/absent value
     * @return the organization identifier, or {@code null} when there is no tenant
     */
    private static String organization(String organizationId) {
        return organizationId == null || organizationId.isBlank() ? null : organizationId;
    }
}
