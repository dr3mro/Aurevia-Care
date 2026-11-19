package com.aurevia.smartclinic.scheduling;

/**
 * Booking command for a weekly series. Every occurrence uses the same visit
 * type, clinician and room, one every {@code repeatWeeks} weeks.
 *
 * @param facilityCode   tenant-local facility code
 * @param patientId      patient identifier in the active organization
 * @param visitType      tenant-local visit type code
 * @param clinicianCode  tenant-local clinician resource code
 * @param roomCode       tenant-local room resource code, or {@code null}
 * @param startsAt       ISO-8601 instant of the first occurrence
 * @param overrideReason recorded reason when a slot is outside availability
 * @param repeatWeeks    weeks between occurrences, 1-4
 * @param occurrences    total number of occurrences, 1-52
 * @return this record
 */
public record SeriesRequest(String facilityCode, String patientId, String visitType,
                            String clinicianCode, String roomCode, String startsAt,
                            String overrideReason, Integer repeatWeeks, Integer occurrences) {
}
