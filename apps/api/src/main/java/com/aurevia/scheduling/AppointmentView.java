package com.aurevia.scheduling;

import java.util.List;

/**
 * An appointment together with its append-only history.
 *
 * @return this record
 * @param appointment value supplied by the caller
 * @param history value supplied by the caller
 */
public record AppointmentView(Appointment appointment, List<AppointmentEvent> history) {

    /**
     * Copy the caller's history so this view cannot be changed through it.
     *
     * @throws NullPointerException when history is null
     */
    public AppointmentView {
        history = List.copyOf(history);
    }

    /**
     * Return the immutable appointment history.
     *
     * @return history events oldest first
     */
    @Override
    public List<AppointmentEvent> history() {
        return List.copyOf(history);
    }
}
