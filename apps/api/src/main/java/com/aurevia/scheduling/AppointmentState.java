package com.aurevia.scheduling;

import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle of one appointment.
 *
 * <p>{@code held} is a temporary reservation with an expiry, {@code expired} is
 * the released form of a hold, and {@code completed}, {@code cancelled},
 * {@code no_show} and {@code expired} are terminal. The legal transitions are
 * enforced here and again by the {@code appointment_update_guard} database
 * trigger, so neither a service bug nor a stray SQL statement can walk an
 * appointment backwards.</p>
 */
public enum AppointmentState {

    /** Temporary reservation that occupies the slot until {@code expires_at}. */
    HELD,
    /** Confirmed appointment; the entry point of the clinical state machine. */
    BOOKED,
    /** The patient has arrived and been checked in. */
    CHECKED_IN,
    /** The encounter is in progress. */
    IN_PROGRESS,
    /** The visit is finished. Terminal. */
    COMPLETED,
    /** The appointment was cancelled with a reason. Terminal. */
    CANCELLED,
    /** The patient did not attend, with a reason. Terminal. */
    NO_SHOW,
    /** A hold lapsed before it was confirmed. Terminal. */
    EXPIRED;

    private static final Set<AppointmentState> OCCUPYING =
            Set.of(HELD, BOOKED, CHECKED_IN, IN_PROGRESS);

    /**
     * Wire form used by the API, the database and history actions.
     *
     * @return lower-case state name such as {@code checked_in}
     */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse the database or API wire form.
     *
     * @param value lower-case state name
     * @return the matching state
     * @throws IllegalArgumentException when the value is unknown
     */
    public static AppointmentState from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether the state holds the protected resource slot.
     *
     * @return {@code true} for held, booked, checked in and in progress
     */
    public boolean occupiesSlot() {
        return OCCUPYING.contains(this);
    }

    /**
     * Whether the state is final.
     *
     * @return {@code true} for completed, cancelled, no show and expired
     */
    public boolean isTerminal() {
        return !OCCUPYING.contains(this);
    }

    /**
     * Whether requires a recorded reason to enter the state.
     *
     * @return {@code true} for cancelled and no show
     */
    public boolean requiresReason() {
        return this == CANCELLED || this == NO_SHOW;
    }

    /**
     * Action name written to the append-only history for a transition into this state.
     *
     * @return history action key
     */
    public String eventAction() {
        return this == IN_PROGRESS ? "started" : wire();
    }

    /**
     * Whether a transition from this state into the target is allowed.
     *
     * @param target requested next state
     * @return {@code true} when the transition is legal
     */
    public boolean canTransitionTo(AppointmentState target) {
        return switch (this) {
            case HELD -> target == BOOKED || target == EXPIRED || target == CANCELLED;
            case BOOKED -> target == CHECKED_IN || target == CANCELLED || target == NO_SHOW;
            case CHECKED_IN -> target == IN_PROGRESS || target == CANCELLED;
            case IN_PROGRESS -> target == COMPLETED;
            default -> false;
        };
    }
}
