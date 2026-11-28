package com.aurevia.exchange.task;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Transmission channel of one exchange task (XCH-02).
 *
 * <p>Transmission events describe partner delivery only. A delivery receipt is
 * deliberately not a clinical acknowledgment: changing this channel can never
 * change the clinical review channel.</p>
 */
public enum TransmissionState {

    /** Nothing has been handed to a partner channel yet. */
    PENDING,
    /** The payload was handed to the approved channel. */
    SENT,
    /** The partner confirmed receipt. Terminal. */
    DELIVERED,
    /** Transmission failed. Terminal: the channel has no outgoing transition. */
    FAILED;

    /**
     * Wire form used by the API, the database and the event ledger.
     *
     * @return lower-case state name such as {@code delivered}
     */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse the database or API wire form.
     *
     * @param value lower-case state name
     * @return the matching transmission state
     * @throws IllegalArgumentException when the value is unknown
     */
    public static TransmissionState from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether the transmission may move to {@code target}.
     *
     * @param target requested next state
     * @return {@code true} when the transition is legal
     */
    public boolean canTransitionTo(TransmissionState target) {
        return switch (this) {
            case PENDING -> target == SENT || target == FAILED;
            case SENT -> target == DELIVERED || target == FAILED;
            default -> false;
        };
    }
}
