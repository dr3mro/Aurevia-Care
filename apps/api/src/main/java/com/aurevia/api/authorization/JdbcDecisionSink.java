package com.aurevia.api.authorization;

import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.tenant.TenantContext;

/**
 * Durable OPS-02 ledger for command authorization outcomes.
 *
 * <p>Each allow or deny decision becomes one append-only row. The tenant is
 * resolved by {@code public.app_org()} inside the tenant-guarded transaction,
 * never from the command payload, so forced row-level security confines every
 * write to the active organization. Only stable codes and opaque identifiers
 * are stored: no request body, patient field or free-text explanation can
 * reach the ledger. A failed or missing write propagates an exception so the
 * caller denies the command instead of proceeding without audit evidence.</p>
 */
public final class JdbcDecisionSink implements CommandAuthorization.DecisionSink {

    /** Maximum length of an audited identifier that is not a bounded code. */
    private static final int IDENTIFIER_LIMIT = 255;
    /** Maximum length of a catalog command identifier. */
    private static final int COMMAND_LIMIT = 128;

    private static final String INSERT = """
            INSERT INTO public.authorization_decision
                (organization_id, subject, action, resource_type, decision,
                 reason_code, correlation_id, decided_at)
            VALUES (public.app_org(), :subject, :action, :resourceType, :decision,
                    :reasonCode, :correlationId, CAST(:decidedAt AS timestamptz))
            """;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transactions;

    /**
     * Create the durable decision sink.
     *
     * @param jdbc          shared named-parameter JDBC operations
     * @param transactions  shared tenant-guarded transaction operations
     */
    public JdbcDecisionSink(NamedParameterJdbcOperations jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /**
     * Append one authorization decision to the tenant ledger.
     *
     * @param decision allow or deny outcome with stable reason code and decision time
     * @throws NullPointerException  when the decision is absent
     * @throws IllegalStateException when no tenant context is bound or no row was written
     */
    @Override
    public void record(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        // The guarded manager binds app_org() only after a transaction opens; without
        // an active context a row would either be written to the wrong tenant or not
        // at all. Fail before touching the database.
        if (TenantContext.current() == null) {
            throw new IllegalStateException("tenant context required to record an authorization decision");
        }
        transactions.executeWithoutResult(status -> {
            int written = jdbc.update(INSERT, new MapSqlParameterSource()
                    .addValue("subject", auditable(decision.subject(), IDENTIFIER_LIMIT))
                    .addValue("action", auditable(decision.action(), COMMAND_LIMIT))
                    .addValue("resourceType", auditable(decision.commandId(), COMMAND_LIMIT))
                    .addValue("decision", decision.allowed() ? "allow" : "deny")
                    .addValue("reasonCode", decision.reasonCode())
                    .addValue("correlationId", auditable(decision.correlationId(), IDENTIFIER_LIMIT))
                    .addValue("decidedAt", decision.occurredAt().toString()));
            // Zero rows means the ledger silently lost the evidence; that is the same
            // failure as a rejected insert and must never be reported as success.
            if (written != 1) {
                throw new IllegalStateException("authorization decision was not recorded");
            }
        });
    }

    /**
     * Bound and clean an audit identifier before it reaches the append-only ledger.
     *
     * <p>Subject and correlation values come from request headers, so control
     * characters must not be able to forge ledger lines. Surrogates and unusual
     * punctuation are folded to {@code _}; the value stays short and enumerable.</p>
     *
     * @param value     value supplied by the decision
     * @param maxLength maximum number of characters retained
     * @return a bounded clean identifier, or {@code null} for an absent value
     */
    private static String auditable(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String bounded = value.length() > maxLength ? value.substring(0, maxLength) : value;
        return bounded.replaceAll("\\p{Cntrl}", "").replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
