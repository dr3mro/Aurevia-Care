package com.aurevia.exchange.conformance;

import java.util.Locale;

/**
 * Recorded processing outcome of one partner event in the conformance ledger.
 */
public enum Disposition {

    /** Applied exactly once with a durable effect. */
    APPLIED,
    /** Immutable event already seen in this sender and type namespace. */
    DUPLICATE,
    /** Per-aggregate version is stale and was held without overwriting. */
    OUT_OF_ORDER,
    /** Identifier did not match a known local identity; queued for reconciliation. */
    UNMATCHED,
    /** Transient failures exhausted the bounded retry budget. */
    DEAD_LETTER;

    /**
     * Stable wire form.
     *
     * @return lower-case name such as {@code dead_letter}
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a wire form.
     *
     * @param value lower-case name
     * @return matching disposition
     * @throws IllegalArgumentException when the name is unknown
     */
    public static Disposition from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
