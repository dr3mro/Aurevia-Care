package com.aurevia.exchange.conformance;

import java.util.Locale;

/**
 * Kind of an immutable partner event used by the synthetic conformance
 * fixtures.
 *
 * <p>The rules exposed here are the conformance facts a receiver must honour:
 * clinical imports need identifier matching, a correction reopens review, and
 * only a clinical acknowledgment may close that review. A transmission
 * acknowledgment deliberately does not.</p>
 */
public enum EventKind {

    /** Released order or requisition. */
    ORDER,
    /** Destination acceptance of an order. */
    ACCEPT,
    /** Transport-level delivery acknowledgment; never a clinical review. */
    TRANSMISSION_ACK,
    /** Accessioned specimen and collection or rejection evidence. */
    SPECIMEN,
    /** Reported result revision. */
    RESULT,
    /** Corrected revision that preserves the prior one. */
    CORRECTION,
    /** Critical finding requiring a named owner and clinical read-back. */
    CRITICAL,
    /** Authorized human acknowledgment of a clinical review. */
    CLINICAL_ACK,
    /** Patient release bound to an immutable revision. */
    RELEASE,
    /** Observation whose trend requires a stable test and unit. */
    OBSERVATION;

    /**
     * Stable wire form.
     *
     * @return lower-case name such as {@code transmission_ack}
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a wire form.
     *
     * @param value lower-case name
     * @return matching kind
     * @throws IllegalArgumentException when the name is unknown
     */
    public static EventKind from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether this kind imports clinical data that must be identifier matched
     * before it can be linked.
     *
     * @return {@code true} for specimen, result, correction, critical and observation
     */
    public boolean requiresIdentifierMatch() {
        return switch (this) {
            case SPECIMEN, RESULT, CORRECTION, CRITICAL, OBSERVATION -> true;
            default -> false;
        };
    }

    /**
     * Whether applying this event opens a clinical review task.
     *
     * @return {@code true} for result, correction and critical
     */
    public boolean opensReview() {
        return switch (this) {
            case RESULT, CORRECTION, CRITICAL -> true;
            default -> false;
        };
    }

    /**
     * Whether this event is a correction that must reopen an already closed review.
     *
     * @return {@code true} only for a correction
     */
    public boolean reopensReview() {
        return this == CORRECTION;
    }

    /**
     * Whether this event is a clinical, human acknowledgment.
     *
     * @return {@code true} only for a clinical acknowledgment
     */
    public boolean isClinicalAck() {
        return this == CLINICAL_ACK;
    }
}
