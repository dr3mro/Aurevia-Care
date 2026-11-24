package com.aurevia.exchange.release;

import java.time.Instant;

/**
 * Lifecycle of one patient release authorization (XCH-06).
 *
 * <p>Terminal states are retained for history and never re-open. Only a held or
 * authorized release can be withdrawn or corrected, so a retracted document stops
 * serving future access while its disclosure history remains intact.</p>
 */
public enum ReleaseState {
    /** Authorized but withheld pending an explicit reviewer decision. */
    HELD,
    /** Authorized; visibility is governed by the effective instant. */
    AUTHORIZED,
    /** Withdrawn; access is revoked and history is preserved. */
    WITHDRAWN,
    /** Superseded by a corrected revision; access is revoked. */
    CORRECTED;

    /**
     * Whether this state permits the named transition.
     *
     * @param target state the command wants to reach
     * @return {@code true} when the transition is legal
     */
    public boolean canTransitionTo(ReleaseState target) {
        return switch (this) {
            case HELD -> target == AUTHORIZED || target == WITHDRAWN;
            case AUTHORIZED -> target == WITHDRAWN || target == CORRECTED;
            case WITHDRAWN, CORRECTED -> false;
        };
    }

    /**
     * Whether a report revision is currently releasable to the patient.
     *
     * @param effectiveFrom instant from which authorization is effective
     * @param now           current instant from the injected clock
     * @return {@code true} only for an authorized release whose time has arrived
     */
    public boolean visibleAt(Instant effectiveFrom, Instant now) {
        return this == AUTHORIZED && !effectiveFrom.isAfter(now);
    }
}
