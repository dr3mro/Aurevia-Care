package com.aurevia.api.authorization;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;

/**
 * Appends command authorization decisions to the durable OPS-02 ledger.
 *
 * <p>Only stable codes and identifiers are written: the command stands in for
 * the resource type, the catalog action for the action, and the government
 * identifiers are bounded. No request body, patient field or free-text reason
 * ever reaches the table. Every value is bound as a parameter and the insert
 * runs inside the caller's tenant-guarded transaction, so forced row-level
 * security confines the row to the active organization.</p>
 *
 * <p>A decision whose organization is absent or malformed cannot enter a
 * tenant-owned row; it is replaced by an {@link com.aurevia.api.authorization.DecisionAuditUnavailable}
 * deny before any SQL. A failed insert has the same outcome: the caller gets a
 * deny with reason {@code AUDIT_UNAVAILABLE} and the command cannot proceed.</p>
 */
public final class JdbcDecisionSink implements DecisionSink {

    /** UUID shape accepted for the active tenant; a blank value is never a tenant. */
    private static final Pattern ORGANIZATION = Pattern.compile(
            "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private static final String INSERT = """
            INSERT INTO public.authorization_decision
                (id, organization_id, subject, action, resource_type, decision,
                 reason_code, correlation_id, decided_at)
            VALUES (CAST(:id AS uuid), CAST(:organizationId AS uuid), :subject, :action,
                    :resourceType, :decision, :reasonCode, :correlationId,
                    CAST(:decidedAt AS timestamptz))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the sink over the shared tenant-bound JDBC template.
     *
     * @param jdbc named-parameter template provided by the production configuration
     * @throws NullPointerException when the template is absent
     */
    public JdbcDecisionSink(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void record(Decision decision) {
        Objects.requireNonNull(decision, "decision");
        String organization = decision.organizationId();
        if (organization == null) {
            throw new DecisionAuditUnavailable(unavailable(decision));
        }
        if (organization.isBlank()) {
            throw new DecisionAuditUnavailable(unavailable(decision));
        }
        if (!ORGANIZATION.matcher(organization).matches()) {
            throw new DecisionAuditUnavailable(unavailable(decision));
        }
        try {
            jdbc.update(INSERT, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString())
                    .addValue("organizationId", organization)
                    .addValue("subject", bound(decision.subject(), 256))
                    .addValue("action", bound(decision.action(), 128))
                    .addValue("resourceType", bound(decision.commandId(), 128))
                    .addValue("decision", decision.allowed() ? "allow" : "deny")
                    .addValue("reasonCode", bound(decision.reasonCode(), 64))
                    .addValue("correlationId", bound(decision.correlationId(), 128))
                    .addValue("decidedAt", decision.occurredAt().toString()));
        } catch (RuntimeException failure) {
            // The ledger is the authority for whether a command may proceed. A
            // broken write must deny rather than leave an allow decision unlogged.
            throw new DecisionAuditUnavailable(unavailable(decision));
        }
    }

    /**
     * Bound an audit identifier before it reaches the append-only ledger.
     *
     * @param value raw decision field, possibly absent
     * @param maximum largest permitted length
     * @return bounded, control-character-free value
     */
    private static String bound(String value, int maximum) {
        String safe = value == null ? "" : value.replaceAll("\\p{Cntrl}", "");
        return safe.length() > maximum ? safe.substring(0, maximum) : safe;
    }

    /**
     * Build the fail-closed replacement for a decision that could not be recorded.
     *
     * @param source unrecorded decision
     * @return deny decision carrying the same evidence and an audit reason code
     */
    private static Decision unavailable(Decision source) {
        return new Decision(source.commandId(), source.action(), source.organizationId(), source.subject(),
                source.correlationId(), false, "AUDIT_UNAVAILABLE", source.rolesUsed(), source.occurredAt());
    }
}
