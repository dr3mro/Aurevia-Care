package com.aurevia.exchange.task;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The three independent state channels of XCH-02.
 *
 * <p>Each command names exactly one dimension, so a single update can never
 * move two channels at once. The policy action of a dimension is also the
 * authorization key checked by
 * {@link com.aurevia.exchange.task.ExchangeTaskService}.</p>
 */
public enum ExchangeDimension {

    /** Destination workflow decisions. */
    WORKFLOW("workflow", "EXCHANGE_WORKFLOW"),
    /** Partner delivery facts. */
    TRANSMISSION("transmission", "EXCHANGE_TRANSMISSION"),
    /** Human clinical review. */
    CLINICAL_REVIEW("clinical_review", "EXCHANGE_REVIEW");

    private final String wire;
    private final String policyAction;

    /**
     * @param wire
     * @param policyAction
     */
    ExchangeDimension(String wire, String policyAction) {
        this.wire = wire;
        this.policyAction = policyAction;
    }

    /**
     * Wire form stored in the event ledger.
     *
     * @return lower-case dimension name
     */
    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * Authorization key required to change this dimension.
     *
     * @return action key
     */
    public String policyAction() {
        return policyAction;
    }

    /**
     * Parse the stored wire form.
     *
     * @param value lower-case dimension name
     * @return the matching dimension
     * @throws IllegalArgumentException when the value is unknown
     */
    public static ExchangeDimension from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
