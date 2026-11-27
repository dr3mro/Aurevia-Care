package com.aurevia.exchange.response;

import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Recipient-side lifecycle of one exchanged order work item.
 *
 * <p>Legal transitions are enforced by the guarded command service; this enum only
 * classifies terminal states. A transmission event never moves this state machine. {@code rejected},
 * {@code cancelled} and {@code revoked} are terminal. A change or cancellation
 * after acceptance always passes through a pending state that needs an explicit
 * recipient response.</p>
 */
public enum ExchangeState {

    /** Released order waiting for a recipient decision. */
    REQUESTED,
    /** Accepted with a locally verified patient association. */
    ACCEPTED,
    /** Rejected with a recorded reason. Terminal. */
    REJECTED,
    /** Clarification requested from the sender with a recorded question. */
    CLARIFICATION_REQUESTED,
    /** A linked new revision awaits a recipient response. */
    CHANGE_PENDING,
    /** A cancellation request awaits a recipient response. */
    CANCELLATION_PENDING,
    /** Cancellation confirmed by the recipient. Terminal. */
    CANCELLED,
    /** Sender revoked the release; retrieval is blocked. Terminal. */
    REVOKED;

    private static final Set<ExchangeState> TERMINAL =
            Set.of(REJECTED, CANCELLED, REVOKED);

    /**
     * Wire form used by the API, the database and the append-only history.
     *
     * @return lower-case state name such as {@code change_pending}
     */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a database or API wire form.
     *
     * @param value lower-case state name
     * @return the matching state
     * @throws java.lang.IllegalArgumentException when the value is unknown
     */
    public static ExchangeState from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether no further transition is allowed.
     *
     * @return {@code true} for rejected, cancelled and revoked
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
