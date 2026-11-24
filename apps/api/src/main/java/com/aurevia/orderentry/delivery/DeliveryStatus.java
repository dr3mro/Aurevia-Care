package com.aurevia.orderentry.delivery;

import java.util.Locale;

/**
 * Current delivery state of one placed order (XCH-02, PRD-02).
 *
 * <p>This is the transmission and fallback channel of the order only. Clinical
 * review state is owned by the exchange task package and is never changed by a
 * delivery fact: a delivery confirmation is not a clinical acknowledgment.</p>
 */
public enum DeliveryStatus {

    /** No delivery attempt has been made. */
    PENDING,
    /** The order was handed to the approved channel. */
    SUBMITTED,
    /** The partner confirmed receipt. */
    DELIVERED,
    /** The recipient accepted the order. */
    ACCEPTED,
    /** The recipient rejected the order. */
    REJECTED,
    /** The recipient asked a clarification. */
    CLARIFICATION,
    /** The bounded retry budget was exhausted; clinician action is required. */
    NEEDS_ACTION,
    /** The send outcome is unknown and must be reconciled before any resend. */
    AMBIGUOUS,
    /** The order was delivered by an approved manual workflow. */
    MANUAL,
    /** The order was switched to the printed fallback channel. */
    PRINT;

    /**
     * Stable lower-case wire key.
     *
     * @return the lower-case enum name
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
