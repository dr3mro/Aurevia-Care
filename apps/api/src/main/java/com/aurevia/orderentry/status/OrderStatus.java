package com.aurevia.orderentry.status;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The one status exposed for a clinical order (LAB-01, LAB-02, RAD-01, XCH-03).
 *
 * <p>The five workflow milestones are ordered by {@code rank} so a status can
 * advance but never go backwards. {@code RESULTED} and {@code CANCELLED} are
 * terminal: a result link and a cancellation are commands with their own
 * invariants and are never reached through the generic milestone transition.
 * Each constant carries the fact kind recorded when it is entered.</p>
 */
public enum OrderStatus {

    /** Editable order not yet placed. */
    DRAFT(0, "draft", StatusEventKind.OPENED),
    /** Placed and awaiting partner delivery. */
    PLACED(1, "placed", StatusEventKind.PLACED),
    /** Handed to the performing partner. */
    DELIVERED(2, "delivered", StatusEventKind.DELIVERED),
    /** Partner accepted the order. */
    ACCEPTED(3, "accepted", StatusEventKind.ACCEPTED),
    /** Specimen collected (lab) or study scheduled (radiology). */
    COLLECTED_OR_SCHEDULED(4, "collected_or_scheduled", StatusEventKind.COLLECTED_OR_SCHEDULED),
    /** A result has been linked back to this order. */
    RESULTED(5, "resulted", StatusEventKind.RESULT_LINKED),
    /** Order cancelled; no further workflow transition applies. */
    CANCELLED(6, "cancelled", StatusEventKind.CANCELLED);

    private final int rank;
    private final String wire;
    private final StatusEventKind eventKind;

    OrderStatus(int rank, String wire, StatusEventKind eventKind) {
        this.rank = rank;
        this.wire = wire;
        this.eventKind = eventKind;
    }

    /**
     * Return the stable lower-case wire key.
     *
     * @return wire key such as {@code collected_or_scheduled}
     */
    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * Return the forward-order rank of this status.
     *
     * @return zero-based rank; higher ranks are later workflow milestones
     */
    public int rank() {
        return rank;
    }

    /**
     * Return the status-trail fact recorded when this status is entered.
     *
     * @return the matching fact kind
     */
    public StatusEventKind eventKind() {
        return eventKind;
    }

    /**
     * Whether this status is a strictly later workflow milestone than another.
     *
     * @param other status to compare against
     * @return {@code true} only when this rank is greater than the other rank
     */
    public boolean isAfter(OrderStatus other) {
        return rank > other.rank;
    }

    /**
     * Whether the partner has already accepted the order at this status.
     *
     * @return {@code true} for accepted and every later workflow status
     */
    public boolean isAfterAcceptance() {
        return rank >= ACCEPTED.rank;
    }

    /**
     * Whether this status ends the order workflow.
     *
     * @return {@code true} for resulted and cancelled
     */
    public boolean isTerminal() {
        return this == RESULTED || this == CANCELLED;
    }
}
