package com.aurevia.scheduling;

/**
 * Booking command for a recurrence series. Every occurrence uses the same visit
 * type, clinician and room, one every {@code repeatWeeks} weeks. The series is
 * bounded by exactly one of a fixed {@code occurrences} count or an inclusive
 * {@code endDate} expressed as an ISO-8601 local date in the facility zone.
 *
 * @param facilityCode   tenant-local facility code
 * @param patientId      patient identifier in the active organization
 * @param visitType      tenant-local visit type code
 * @param clinicianCode  tenant-local clinician resource code
 * @param roomCode       tenant-local room resource code, or {@code null}
 * @param startsAt       ISO-8601 instant of the first occurrence
 * @param overrideReason recorded reason when a slot is outside availability
 * @param repeatWeeks    weeks between occurrences, 1-4
 * @param occurrences    fixed number of occurrences, 1-52, or {@code null} with an end date
 * @param endDate        inclusive ISO-8601 local end date, or {@code null} with a fixed count
 * @return this record
 */
public record SeriesRequest(String facilityCode, String patientId, String visitType,
                            String clinicianCode, String roomCode, String startsAt,
                            String overrideReason, Integer repeatWeeks, Integer occurrences,
                            String endDate) {

    /**
     * Backwards-compatible command for a fixed-count weekly series.
     *
     * @param facilityCode   tenant-local facility code
     * @param patientId      patient identifier in the active organization
     * @param visitType      tenant-local visit type code
     * @param clinicianCode  tenant-local clinician resource code
     * @param roomCode       tenant-local room resource code, or {@code null}
     * @param startsAt       ISO-8601 instant of the first occurrence
     * @param overrideReason recorded reason when a slot is outside availability
     * @param repeatWeeks    weeks between occurrences, 1-4
     * @param occurrences    fixed number of occurrences, 1-52
     */
    public SeriesRequest(String facilityCode, String patientId, String visitType,
                         String clinicianCode, String roomCode, String startsAt,
                         String overrideReason, Integer repeatWeeks, Integer occurrences) {
        this(facilityCode, patientId, visitType, clinicianCode, roomCode, startsAt,
                overrideReason, repeatWeeks, occurrences, null);
    }
}
