package com.aurevia.orderentry.status;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Kind of an immutable fact recorded in one order's status trail (LAB-01, LAB-02, RAD-01, XCH-03).
 *
 * <p>Each kind is a distinct fact. Workflow milestones are separate from cancel
 * requests, partner notifications and result links so a status view can always
 * explain how it reached its current single status. Transmission and clinical
 * review facts continue to be owned by the delivery and result workflows; this
 * vocabulary records only the order-lifecycle view of those facts.</p>
 */
public enum StatusEventKind {

    /** The draft status was opened. */
    OPENED,
    /** The draft was placed. */
    PLACED,
    /** The placed order reached the partner. */
    DELIVERED,
    /** The partner accepted the order. */
    ACCEPTED,
    /** A specimen was collected or an imaging study was scheduled. */
    COLLECTED_OR_SCHEDULED,
    /** The order was amended into a new linked revision. */
    AMENDED,
    /** The partner was notified of an amend revision. */
    PARTNER_NOTIFIED,
    /** The order was cancelled before partner acceptance. */
    CANCELLED,
    /** A cancellation was requested from the partner after acceptance. */
    CANCEL_REQUESTED,
    /** The partner confirmed a post-acceptance cancellation request. */
    CANCEL_REQUEST_CONFIRMED,
    /** The partner declined a post-acceptance cancellation request. */
    CANCEL_REQUEST_DENIED,
    /** A result was linked back to this order. */
    RESULT_LINKED;

    /**
     * Return the stable lower-case wire key.
     *
     * @return the lower-case enum name
     */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
