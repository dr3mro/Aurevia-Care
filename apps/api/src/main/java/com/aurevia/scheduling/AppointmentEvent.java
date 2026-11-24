package com.aurevia.scheduling;

import java.time.Instant;

/**
 * One immutable entry of the appointment history.
 *
 * @param id                opaque event identifier
 * @param appointmentVersion revision of the appointment the event describes
 * @param action            history action key such as {@code rescheduled}
 * @param fromState         previous state, or {@code null} for creation
 * @param toState           resulting state
 * @param reason            recorded reason, or {@code null}
 * @param previousStartsAt  slot before a reschedule, otherwise {@code null}
 * @param previousEndsAt    slot end before a reschedule, otherwise {@code null}
 * @param actorId           authenticated user identity that performed the action
 * @param occurredAt        database clock instant of the event
 * @return this record
 */
public record AppointmentEvent(String id, int appointmentVersion, String action, String fromState,
                               String toState, String reason, Instant previousStartsAt,
                               Instant previousEndsAt, String actorId, Instant occurredAt) {
}
