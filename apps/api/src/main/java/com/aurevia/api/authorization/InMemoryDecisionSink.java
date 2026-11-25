package com.aurevia.api.authorization;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.aurevia.api.authorization.CommandAuthorization.Decision;

/**
 * Synthetic, process-local decision ledger used by unit tests and local rehearsal.
 *
 * <p>This is a reference sink only. Production uses the tenant-guarded JDBC
 * sink, whose independent transaction preserves denial evidence across command
 * rollback; this class never holds clinical data or credentials.</p>
 */
public final class InMemoryDecisionSink implements CommandAuthorization.DecisionSink {

    private final List<Decision> decisions = new ArrayList<>();

    /**
     * Create an empty ledger.
     */
    public InMemoryDecisionSink() {
    }

    @Override
    public void record(Decision decision) {
        decisions.add(Objects.requireNonNull(decision, "decision"));
    }

    /**
     * Return a snapshot of all recorded decisions in order.
     *
     * @return immutable decision list
     */
    public List<Decision> decisions() {
        return List.copyOf(decisions);
    }

    /** Remove all recorded decisions. */
    public void clear() {
        decisions.clear();
    }
}
