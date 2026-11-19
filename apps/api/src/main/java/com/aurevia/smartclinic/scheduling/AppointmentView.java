package com.aurevia.smartclinic.scheduling;

import java.util.List;

/**
 * An appointment together with its append-only history.
 *
 * @param appointment current appointment projection
 * @param history     history oldest first
 */
public record AppointmentView(Appointment appointment, List<AppointmentEvent> history) {
}
