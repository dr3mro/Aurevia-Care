package com.aurevia.api.authorization;

import java.util.Objects;

import com.aurevia.api.authorization.CommandAuthorization.Decision;

/**
 * Fail-closed signal for an authorization decision that could not be persisted.
 *
 * <p>The exception carries the <em>denial</em> that must replace the evaluated
 * outcome when the OPS-02 ledger is unavailable. No row can be written by a
 * failing ledger, so the denial is an in-memory decision available to the
 * future HTTP caller for its problem details; it never authorizes a command.
 * The original decision is kept
 * only as field values, never as a request body or patient data.</p>
 */
public final class DecisionAuditException extends RuntimeException {

    /** Stable reason code carried by the synthetic denial. */
    public static final String REASON_CODE = "AUDIT_UNAVAILABLE";

    private static final long serialVersionUID = 1L;

    private final Decision denial;

    /**
     * Build the denial for a decision whose durable write failed.
     *
     * @param decision the evaluated decision that must not be allowed
     * @param cause    underlying persistence failure, or {@code null} when the
     *                 failure was a rejected tenant boundary or empty write
     */
    public DecisionAuditException(Decision decision, Throwable cause) {
        super("authorization decision could not be recorded", cause);
        Decision source = Objects.requireNonNull(decision, "decision");
        this.denial = new Decision(source.commandId(), source.action(), source.organizationId(),
                source.subject(), source.correlationId(), false, REASON_CODE, source.rolesUsed(),
                source.occurredAt());
    }

    /**
     * Return the immutable denial that replaces the evaluated outcome.
     *
     * @return a decision with {@code allowed == false} and reason {@code AUDIT_UNAVAILABLE}
     */
    public Decision denial() {
        return denial;
    }
}
