package com.aurevia.api.authorization;

import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.tenant.TenantContext;

/**
 * Durable OPS-02 decision sink over the append-only V65 ledger.
 *
 * <p>Every statement joins the caller's tenant-guarded transaction, so the
 * decision commits with the command it authorizes and no separate in-process
 * copy can diverge on restart. Forced RLS on {@code authorization_decision} is
 * the second isolation barrier; the active organization is also bound
 * explicitly and checked against the decision before any SQL runs. Only
 * bounded codes and identifiers are written: no request body, role list or
 * patient field ever reaches the ledger.</p>
 *
 * <p>Any failure to write fails closed with
 * {@link com.aurevia.api.authorization.DecisionAuditException}, whose denial
 * carries the reason {@code AUDIT_UNAVAILABLE}. A client-supplied command
 * identifier is stripped of control characters and truncated before it can
 * reach an audit column, so an untrusted value cannot forge a new line or
 * exhaust the column.</p>
 */
public final class JdbcDecisionSink implements CommandAuthorization.DecisionSink {

    /** Maximum stored length of the bounded command identifier in resource_type. */
    public static final int RESOURCE_TYPE_MAX = 128;
    /** Maximum stored length of the action key. */
    public static final int ACTION_MAX = 128;
    /** Maximum stored length of the stable reason code. */
    public static final int REASON_CODE_MAX = 64;
    /** Maximum stored length of the authenticated subject. */
    public static final int SUBJECT_MAX = 256;
    /** Maximum stored length of the correlation identifier. */
    public static final int CORRELATION_MAX = 128;

    private static final String INSERT = """
            INSERT INTO public.authorization_decision
                (organization_id, subject, action, resource_type, decision, reason_code, correlation_id)
            VALUES (CAST(:organizationId AS uuid), :subject, :action, :resourceType, :decision,
                    :reasonCode, :correlationId)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the durable sink.
     *
     * @param jdbc shared named-parameter JDBC template; the caller supplies the
     *             tenant-guarded transaction so state and audit commit together
     */
    public JdbcDecisionSink(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void record(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        TenantContext context = TenantContext.current();
        if (context == null || !token(context.organizationId())) {
            throw new DecisionAuditException(decision, null);
        }
        if (!context.organizationId().equals(decision.organizationId())) {
            throw new DecisionAuditException(decision, null);
        }
        SqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", context.organizationId())
                .addValue("subject", bounded(decision.subject(), SUBJECT_MAX))
                .addValue("action", bounded(decision.action(), ACTION_MAX))
                .addValue("resourceType", bounded(decision.commandId(), RESOURCE_TYPE_MAX))
                .addValue("decision", decision.allowed() ? "allow" : "deny")
                .addValue("reasonCode", bounded(decision.reasonCode(), REASON_CODE_MAX))
                .addValue("correlationId", bounded(decision.correlationId(), CORRELATION_MAX));
        int written;
        try {
            written = jdbc.update(INSERT, parameters);
        } catch (RuntimeException failure) {
            throw new DecisionAuditException(decision, failure);
        }
        if (written != 1) {
            throw new DecisionAuditException(decision, null);
        }
    }

    /**
     * Require a present, non-blank server value.
     *
     * @param value value to check
     * @return {@code true} only when the value is present and non-blank
     */
    private static boolean token(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Bound an audited code before it reaches a CHECK-constrained column.
     *
     * @param value source value, possibly client-derived or absent
     * @param max   maximum number of characters the column accepts
     * @return empty text for an absent value, otherwise control characters
     *         removed and the value truncated to {@code max}
     */
    private static String bounded(String value, int max) {
        // Java's Cntrl POSIX class is US-ASCII only; Unicode controls, format
        // overrides and line/paragraph separators can forge an audit line too.
        String safe = value == null ? "" : value.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "");
        // PostgreSQL length() counts code points, so truncate on a code-point
        // boundary rather than leaving half of a surrogate pair in the ledger.
        int codePoints = safe.codePointCount(0, safe.length());
        return codePoints > max ? safe.substring(0, safe.offsetByCodePoints(0, max)) : safe;
    }
}
