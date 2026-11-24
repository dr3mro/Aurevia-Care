package com.aurevia.scheduling;

/**
 * Booking command body. Organization, author and version are never accepted
 * from the client.
 *
 * @param facilityCode   tenant-local facility code
 * @param patientId      patient identifier in the active organization
 * @param visitType      tenant-local visit type code that owns the duration
 * @param clinicianCode  tenant-local clinician resource code
 * @param roomCode       tenant-local room resource code, or {@code null}
 * @param startsAt       ISO-8601 instant of the slot start
 * @param overrideReason recorded reason when the slot is outside availability
 * @return this record
 */
public record BookingRequest(String facilityCode, String patientId, String visitType,
                             String clinicianCode, String roomCode, String startsAt,
                             String overrideReason) {
}
