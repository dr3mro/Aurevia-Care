package com.aurevia.messaging.appointments;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Patient-visible lifecycle of an appointment.
 *
 * <p>The status is authoritative from the scheduling domain; this projection
 * only decides whether a patient confirmation is still meaningful. Mobile
 * clients never create a transition from it.</p>
 */
public enum AppointmentStatus {

    /** Confirmed visit that the patient may still confirm attendance for. */
    BOOKED,
    /** The patient has arrived. */
    CHECKED_IN,
    /** The encounter is running. */
    IN_PROGRESS,
    /** The visit is finished. */
    COMPLETED,
    /** The visit was cancelled. */
    CANCELLED,
    /** The patient did not attend. */
    NO_SHOW;

    private static final Set<AppointmentStatus> TERMINAL =
            EnumSet.of(COMPLETED, CANCELLED, NO_SHOW);

    /**
     * Return the lower-case wire form used by the API.
     *
     * @return wire value such as {@code checked_in}
     */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a patient confirmation is meaningful in this status.
     *
     * @return {@code true} only for a booked appointment
     */
    public boolean patientCanConfirm() {
        return this == BOOKED;
    }

    /**
     * Whether the appointment has reached a final state.
     *
     * @return {@code true} for completed, cancelled and no-show
     */
    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Parse the wire form.
     *
     * @param value lower-case or upper-case state name
     * @return the matching status
     * @throws IllegalArgumentException when the value is unknown
     */
    public static AppointmentStatus from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
