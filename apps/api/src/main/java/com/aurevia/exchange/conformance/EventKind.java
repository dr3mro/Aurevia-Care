package com.aurevia.exchange.conformance;

import java.util.Locale;

/**
 * Kind of an immutable partner event used by the synthetic conformance
 * fixtures (XCH-01 to XCH-07, LAB-01, LAB-02).
 *
 * <p>The rules exposed here are the conformance facts a receiver must honour:
 * clinical imports need identifier matching, a correction reopens review, and
 * only a clinical acknowledgment may close that review. A transmission
 * acknowledgment deliberately does not.</p>
 */
public enum EventKind {

    /** Released order or requisition (XCH-01). */
    ORDER,
    /** Destination acceptance of an order (XCH-03). */
    ACCEPT,
    /** Transport-level delivery acknowledgment; never a clinical review (XCH-02). */
    TRANSMISSION_ACK,
    /** Accessioned specimen and collection or rejection evidence (LAB-01). */
    SPECIMEN,
    /** Reported result revision (XCH-04, LAB-02). */
    RESULT,
    /** Corrected revision that preserves the prior one (XCH-04, LAB-02). */
    CORRECTION,
    /** Critical finding requiring a named owner and clinical read-back (XCH-05). */
    CRITICAL,
    /** Authorized human acknowledgment of a clinical review (XCH-02, XCH-05). */
    CLINICAL_ACK,
    /** Patient release bound to an immutable revision (XCH-06). */
    RELEASE,
    /** Observation whose trend requires a stable test and unit (XCH-07, LAB-02). */
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
     * before it can be linked (XCH-04, LAB-01).
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
