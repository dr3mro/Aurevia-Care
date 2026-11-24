package com.aurevia.exchange.task;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Workflow channel of one exchange task (XCH-02).
 *
 * <p>This channel is independent of transmission and clinical review: accepting
 * an order never records a delivery, and completing it never acknowledges a
 * clinical finding. The same matrix is enforced by the V20 database guard.</p>
 */
public enum WorkflowState {

    /** Order created at the sending organization and awaiting destination decision. */
    REQUESTED,
    /** Destination accepted the work item. */
    ACCEPTED,
    /** Destination is actively working the item. */
    PROCESSING,
    /** Work finished. Terminal. */
    COMPLETED,
    /** Destination declined the item with a reason. Terminal. */
    REJECTED,
    /** Sender or destination cancelled the item with a reason. Terminal. */
    CANCELLED;

    /**
     * Wire form used by the API, the database and the event ledger.
     *
     * @return lower-case state name such as {@code accepted}
     */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse the database or API wire form.
     *
     * @param value lower-case state name
     * @return the matching workflow state
     * @throws IllegalArgumentException when the value is unknown
     */
    public static WorkflowState from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether the state is final.
     *
     * @return {@code true} for completed, rejected and cancelled
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == CANCELLED;
    }

    /**
     * Whether the workflow may move to {@code target}.
     *
     * @param target requested next state
     * @return {@code true} when the transition is legal
     */
    public boolean canTransitionTo(WorkflowState target) {
        return switch (this) {
            case REQUESTED -> target == ACCEPTED || target == REJECTED || target == CANCELLED;
            case ACCEPTED -> target == PROCESSING || target == CANCELLED;
            case PROCESSING -> target == COMPLETED || target == CANCELLED;
            default -> false;
        };
    }
}
