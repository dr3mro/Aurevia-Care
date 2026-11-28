package com.aurevia.scheduling.followup;

import java.time.LocalDate;

/**
 * The bounded payload handed to the booking port on explicit confirmation.
 *
 * <p>The domain does not choose a clinician, room or slot; the booking adapter
 * applies the scheduling rules and returns the created appointment identifier.</p>
 *
 * @param followUpId     source suggestion identifier
 * @param organizationId owning tenant
 * @param patientId      tenant-local patient identifier
 * @param facilityCode   tenant-local facility code
 * @param visitType      visit type code
 * @param followUpDate   effective facility-local civil date
 * @param timeZone       IANA zone for interpreting the civil date
 * @return this record
 */
public record BookingIntent(String followUpId, String organizationId, String patientId,
                            String facilityCode, String visitType, LocalDate followUpDate,
                            String timeZone) {
}
