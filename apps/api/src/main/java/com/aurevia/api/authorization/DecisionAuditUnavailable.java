package com.aurevia.api.authorization;

import java.util.Objects;

import com.aurevia.api.authorization.CommandAuthorization.Decision;

/**
 * Signals that an authorization decision could not be appended to its ledger.
 *
 * <p>The exception carries the deny decision that replaces the attempted
 * outcome: an audit failure must never be mistaken for an allowed command.
 * Callers fail closed; the carried decision is evidence for the owning
 * service's error handling, not a second attempt to write a broken ledger.</p>
 */
public final class DecisionAuditUnavailable extends RuntimeException {

    private final Decision decision;

    /**
     * Create the failure with its replacement deny decision.
     *
     * @param decision deny decision with reason code {@code AUDIT_UNAVAILABLE}
     * @throws NullPointerException when the decision is absent
     */
    public DecisionAuditUnavailable(Decision decision) {
        super("authorization decision could not be recorded");
        this.decision = Objects.requireNonNull(decision, "decision");
    }

    /**
     * Return the deny decision that replaces the unrecorded outcome.
     *
     * @return the immutable deny decision
     */
    public Decision decision() {
        return decision;
    }
}
