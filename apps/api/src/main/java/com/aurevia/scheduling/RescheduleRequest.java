package com.aurevia.scheduling;

/**
 * Reschedule command body. A {@code null} resource keeps the current assignment.
 *
 * @param startsAt       ISO-8601 instant of the new slot start
 * @param clinicianCode  replacement clinician resource code, or {@code null}
 * @param roomCode       replacement room resource code, or {@code null}
 * @param overrideReason recorded reason when the new slot is outside availability
 * @return this record
 */
public record RescheduleRequest(String startsAt, String clinicianCode, String roomCode,
                                String overrideReason) {
}
