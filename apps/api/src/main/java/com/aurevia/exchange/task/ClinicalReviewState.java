package com.aurevia.exchange.task;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Clinical review channel of one exchange task (XCH-02).
 *
 * <p>Only an authorized human clinician may move this channel. Each change is
 * bound to the exact order revision, an actor derived from the authenticated
 * context and a timestamp from the injected clock; a transmission event can
 * never write here.</p>
 */
public enum ClinicalReviewState {

    /** No clinician has reviewed the item. */
    UNREVIEWED,
    /** A clinician acknowledged receipt of this exact revision. */
    ACKNOWLEDGED,
    /** The clinician recorded follow-up work. */
    ACTION_PENDING,
    /** The clinical task is resolved. Terminal. */
    RESOLVED;

    /**
     * Wire form used by the API, the database and the event ledger.
     *
     * @return lower-case state name such as {@code action_pending}
     */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse the database or API wire form.
     *
     * @param value lower-case state name
     * @return the matching clinical review state
     * @throws IllegalArgumentException when the value is unknown
     */
    public static ClinicalReviewState from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether the review may move to {@code target}.
     *
     * @param target requested next state
     * @return {@code true} when the transition is legal
     */
    public boolean canTransitionTo(ClinicalReviewState target) {
        return switch (this) {
            case UNREVIEWED -> target == ACKNOWLEDGED;
            case ACKNOWLEDGED -> target == ACTION_PENDING;
            case ACTION_PENDING -> target == RESOLVED;
            default -> false;
        };
    }
}
