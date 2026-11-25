package com.aurevia.api.authorization;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.tenant.TenantContext;

/**
 * Durable OPS-02 sink that appends one authorization decision to PostgreSQL.
 *
 * <p>The tenant is taken only from the request-scoped {@code com.aurevia.tenant.TenantContext},
 * never from the decision or its client-derived fields. The insert runs inside a
 * fresh tenant-guarded transaction so a denial is not erased when the owning
 * command rolls back; the trade-off is that an allowed decision remains recorded
 * even if the command later fails. Only coded columns are persisted: no role
 * list, request body, credential or patient value reaches the ledger. A
 * constraint violation, missing context or cross-tenant decision denies the
 * request with {@code AUDIT_UNAVAILABLE} using a fixed error message, so no SQL
 * or bind detail is echoed to the caller.</p>
 */
public final class JdbcDecisionSink implements CommandAuthorization.DecisionSink {

    /** Fixed discriminator stored in place of a client-controlled resource string. */
    static final String RESOURCE_TYPE = "COMMAND";

    /** Failure code returned when the ledger cannot record a decision. */
    static final String AUDIT_UNAVAILABLE = "AUDIT_UNAVAILABLE";

    /** Fixed, non-sensitive failure message. */
    static final String FAILURE_MESSAGE = "authorization decision could not be recorded";

    /** Reason codes emitted by the gate; the V65 constraint allows exactly these. */
    static final Set<String> REASON_CODES = Set.of(
            "ALLOWED", "UNAUTHENTICATED", "INVALID_COMMAND", "UNKNOWN_COMMAND",
            "MEMBERSHIP_INACTIVE", "MEMBERSHIP_UNAVAILABLE", "POLICY_UNAVAILABLE",
            "COMMAND_FORBIDDEN", AUDIT_UNAVAILABLE);

    private static final String INSERT = """
            INSERT INTO public.authorization_decision
                (organization_id, subject, action, resource_type, decision,
                 reason_code, correlation_id, decided_at)
            VALUES (CAST(:organizationId AS uuid), :subject, :action, :resourceType,
                    :decision, :reasonCode, :correlationId, CAST(:decidedAt AS timestamptz))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the durable sink.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template for the ledger write
     */
    public JdbcDecisionSink(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void record(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        TenantContext context = TenantContext.current();
        String organization = context == null ? null : context.organizationId();
        if (organization == null || organization.isBlank()) {
            throw unavailable();
        }
        String decidedFor = decision.organizationId();
        if (decidedFor != null && !decidedFor.isBlank() && !decidedFor.equals(organization)) {
            // The decision's tenant is evidence, never an authority to write under.
            throw unavailable();
        }
        if (decision.reasonCode() == null || !REASON_CODES.contains(decision.reasonCode())) {
            throw unavailable();
        }
        String outcome = decision.allowed() ? "allow" : "deny";
        try {
            transactions.execute(status -> jdbc.update(INSERT, new MapSqlParameterSource()
                    .addValue("organizationId", organization)
                    .addValue("subject", decision.subject())
                    .addValue("action", decision.action())
                    .addValue("resourceType", RESOURCE_TYPE)
                    .addValue("decision", outcome)
                    .addValue("reasonCode", decision.reasonCode())
                    .addValue("correlationId", decision.correlationId())
                    .addValue("decidedAt", OffsetDateTime.ofInstant(decision.occurredAt(), ZoneOffset.UTC))));
        } catch (RuntimeException failure) {
            // Never propagate a JDBC message: it can name tables, binds or tenants.
            throw unavailable();
        }
    }

    /**
     * Build the single fixed deny used for every ledger failure.
     *
     * @return the machine-readable fail-closed error
     */
    private static ApiException unavailable() {
        return new ApiException(403, AUDIT_UNAVAILABLE, FAILURE_MESSAGE);
    }
}
