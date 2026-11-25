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

    /** Maximum length of an audited subject identifier. */
    private static final int SUBJECT_LIMIT = 255;
    /** Maximum length of an audited action code. */
    private static final int ACTION_LIMIT = 128;
    /** Maximum correlation length accepted by the V65 ledger constraint. */
    private static final int CORRELATION_LIMIT = 128;

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

    /**
     * Append one authorization decision to the active tenant's ledger.
     *
     * @param decision allow or deny outcome with stable reason code and decision time
     * @throws NullPointerException  when the decision is absent
     * @throws ApiException         {@code 403 AUDIT_UNAVAILABLE} when the tenant or reason is
     *                             invalid, the insert fails, or no row was written
     */
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
        String reason = decision.reasonCode();
        if (reason == null || !REASON_CODES.contains(reason)) {
            throw unavailable();
        }
        String outcome = decision.allowed() ? "allow" : "deny";
        try {
            transactions.execute(status -> {
                int written = jdbc.update(INSERT, new MapSqlParameterSource()
                        .addValue("organizationId", organization)
                        .addValue("subject", auditable(decision.subject(), SUBJECT_LIMIT))
                        .addValue("action", auditable(decision.action(), ACTION_LIMIT))
                        .addValue("resourceType", RESOURCE_TYPE)
                        .addValue("decision", outcome)
                        .addValue("reasonCode", reason)
                        .addValue("correlationId", auditable(decision.correlationId(), CORRELATION_LIMIT))
                        .addValue("decidedAt", OffsetDateTime.ofInstant(decision.occurredAt(), ZoneOffset.UTC)));
                // A silent zero-row write is the same lost evidence as a rejected insert.
                if (written != 1) {
                    throw new IllegalStateException("authorization decision was not recorded");
                }
                return written;
            });
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

    /**
     * Bound and clean an audit identifier before it reaches the append-only ledger.
     *
     * <p>Subject and correlation values come from request headers, so control,
     * format and Unicode line-separator characters must not be able to forge
     * ledger lines. Other printable punctuation is preserved because collapsing
     * distinct issuer-qualified subjects into the same value would corrupt audit
     * evidence. Cleaning happens before truncation so leading control characters
     * cannot erase an otherwise meaningful identifier. Absent values become the
     * empty string required by the non-null V65 columns.</p>
     *
     * @param value     value supplied by the decision
     * @param maxLength maximum number of characters retained
     * @return a bounded clean identifier, or an empty string for an absent value
     */
    private static String auditable(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = value.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", "");
        return clean.length() > maxLength ? clean.substring(0, maxLength) : clean;
    }
}
