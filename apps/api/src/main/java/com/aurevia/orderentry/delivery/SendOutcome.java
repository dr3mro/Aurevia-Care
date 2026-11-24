package com.aurevia.orderentry.delivery;

import java.util.Locale;

/**
 * Result reported by an approved partner channel for one send attempt (XCH-02).
 */
public enum SendOutcome {

    /** The partner confirmed receipt. */
    DELIVERED,
    /** The attempt failed transiently and may be retried within the budget. */
    FAILED,
    /** The outcome is unknown; the send must be reconciled before any resend. */
    AMBIGUOUS;

    /**
     * Stable lower-case wire key.
     *
     * @return the lower-case enum name
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
