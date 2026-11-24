package com.aurevia.scheduling;

import java.time.Instant;

/**
 * One appointment projection with the tenant-local codes a caller can display.
 *
 * @param id             opaque appointment identifier
 * @param version        optimistic-lock version used by {@code If-Match}
 * @param patientId      patient identifier in the active organization
 * @param facilityCode   tenant-local facility code
 * @param clinicianCode  tenant-local clinician resource code
 * @param roomCode       tenant-local room resource code, or {@code null}
 * @param visitType      tenant-local visit type code
 * @param startsAt       UTC slot start
 * @param endsAt         UTC slot end
 * @param state          effective lifecycle state
 * @param seriesId       shared identifier of a weekly series, or {@code null}
 * @param expiresAt      hold expiry, or {@code null} for a confirmed appointment
 * @param overrideReason recorded availability override, or {@code null}
 * @return this record
 */
public record Appointment(String id, int version, String patientId, String facilityCode,
                          String clinicianCode, String roomCode, String visitType,
                          Instant startsAt, Instant endsAt, AppointmentState state, String seriesId,
                          Instant expiresAt, String overrideReason) {
}
