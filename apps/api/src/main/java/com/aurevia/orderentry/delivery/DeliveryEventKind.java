package com.aurevia.orderentry.delivery;

import java.util.Locale;

/**
 * Kind of an append-only order-delivery fact (XCH-01, XCH-02, XCH-03, PRD-02).
 *
 * <p>Each kind is a distinct fact in the delivery ledger. Transmission facts
 * ({@code SUBMITTED}, {@code DELIVERY_CONFIRMED}, {@code DELIVERY_FAILED},
 * {@code AMBIGUOUS_SEND}, {@code RECONCILED}) describe partner delivery only.
 * Partner workflow responses ({@code ACCEPTED}, {@code REJECTED},
 * {@code CLARIFICATION_ASKED}) describe the recipient's answer and never close
 * a clinical review task. {@code MANUAL_DELIVERY} and
 * {@code SWITCHED_TO_PRINT} record non-electronic fallback channels with their
 * own evidence (PRD-02).</p>
 */
public enum DeliveryEventKind {

    /** The order was handed to the approved partner channel. */
    SUBMITTED,
    /** The partner confirmed transport-level receipt; not a clinical acknowledgment. */
    DELIVERY_CONFIRMED,
    /** The recipient accepted the order. */
    ACCEPTED,
    /** The recipient rejected the order with a reason. */
    REJECTED,
    /** The recipient asked a clinical clarification. */
    CLARIFICATION_ASKED,
    /** One bounded send attempt failed. */
    DELIVERY_FAILED,
    /** The order was handed over by an approved manual workflow with evidence. */
    MANUAL_DELIVERY,
    /** The send outcome could not be determined and must be reconciled. */
    AMBIGUOUS_SEND,
    /** An authorized actor reconciled an ambiguous send. */
    RECONCILED,
    /** The clinician switched this order to the printed fallback channel. */
    SWITCHED_TO_PRINT;

    /**
     * Stable lower-case wire key.
     *
     * @return the lower-case enum name
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a wire key.
     *
     * @param value lower-case or upper-case kind name
     * @return the matching kind
     * @throws IllegalArgumentException when the name is unknown
     */
    public static DeliveryEventKind from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
