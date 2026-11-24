package com.aurevia.scheduling.checkin;

import java.time.Instant;

/**
 * One assignable row of the organization/facility worklist (CLN-02).
 *
 * <p>A row is either a scheduled appointment (with a start time) or an
 * unscheduled arrival linked to intake (with an intake reason). Assignment is
 * a current projection with an assignee and timestamp; every transfer also
 * leaves an immutable
 * {@link com.aurevia.scheduling.checkin.HandoffEvent}.</p>
 *
 * @param id              opaque worklist entry identifier
 * @param appointmentId   scheduled appointment, or {@code null} for a walk-in
 * @param intakeReason    unscheduled-arrival reason, or {@code null} for a booking
 * @param patientId       patient the item belongs to
 * @param facilityCode    facility code that scopes the worklist
 * @param startsAt        scheduled start, or {@code null} for a walk-in
 * @param priority        configured priority, higher sorts first within a time
 * @param assigneeSubject active member currently assigned, or {@code null}
 * @param assignedAt      instant of the current assignment, or {@code null}
 * @param version         optimistic-concurrency version of the current projection
 * @return this record
 */
public record WorklistItem(String id, String appointmentId, String intakeReason, String patientId,
                           String facilityCode, Instant startsAt, int priority,
                           String assigneeSubject, Instant assignedAt, int version) {
}
