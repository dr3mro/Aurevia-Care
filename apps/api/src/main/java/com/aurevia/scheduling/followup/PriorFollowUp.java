package com.aurevia.scheduling.followup;

import java.time.LocalDate;

/**
 * An existing follow-up the tenant considers applicable to an encounter (CLN-06).
 *
 * <p>The owning port applies eligibility rules such as tenant scope, visit type
 * and a non-terminal booking before returning one; the domain never links a
 * patient or a date from demographics alone.</p>
 *
 * @param appointmentId source appointment identifier
 * @param date          facility-local civil date of the existing follow-up
 * @return this record
 */
public record PriorFollowUp(String appointmentId, LocalDate date) {
}
