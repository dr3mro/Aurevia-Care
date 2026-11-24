package com.aurevia.scheduling.followup;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Server-owned follow-up suggestion with an auditable rule explanation (CLN-06).
 *
 * <p>The suggested civil date is immutable until an authorized override appends
 * provenance. A booking is created only by the separate confirm command, which
 * stores the returned booking identifier on a new version of this record.</p>
 *
 * @param id              opaque suggestion identifier
 * @param organizationId  owning tenant
 * @param patientId       tenant-local patient identifier
 * @param facilityCode    tenant-local facility code
 * @param visitType       visit type used to resolve the interval
 * @param encounterAt     UTC instant of the encounter
 * @param timeZone        IANA zone used for civil-date arithmetic
 * @param suggestedDate   current effective civil date
 * @param intervalDays    configured interval, or {@code null} when a prior follow-up governed
 * @param basis           {@code CONFIGURED_INTERVAL} or {@code PRIOR_FOLLOW_UP}
 * @param ruleExplanation human-readable rule and provenance explanation
 * @param override        recorded override, or {@code null}
 * @param confirmed       whether explicit confirmation created a booking
 * @param bookingId       identifier returned by the booking port, or {@code null}
 * @param version         optimistic-concurrency version
 * @return this record
 */
public record FollowUpSuggestion(String id, String organizationId, String patientId,
                                 String facilityCode, String visitType, Instant encounterAt,
                                 String timeZone, LocalDate suggestedDate, Integer intervalDays,
                                 String basis, String ruleExplanation, FollowUpOverride override,
                                 boolean confirmed, String bookingId, int version) {

    /**
     * Apply an override, keeping the original rule explanation visible.
     *
     * @param value recorded provenance
     * @return a new version carrying the replacement date
     */
    public FollowUpSuggestion withOverride(FollowUpOverride value) {
        return new FollowUpSuggestion(id, organizationId, patientId, facilityCode, visitType,
                encounterAt, timeZone, value.replacementDate(), intervalDays, basis,
                ruleExplanation + "; overridden by " + value.actor() + " at " + value.recordedAt()
                        + " because " + value.reason() + " (was " + suggestedDate + ")",
                value, false, bookingId, version + 1);
    }

    /**
     * Mark the suggestion confirmed after the booking port succeeds.
     *
     * @param bookingReference identifier returned by the booking port
     * @return a new confirmed version
     */
    public FollowUpSuggestion confirmed(String bookingReference) {
        return new FollowUpSuggestion(id, organizationId, patientId, facilityCode, visitType,
                encounterAt, timeZone, suggestedDate, intervalDays, basis, ruleExplanation,
                override, true, bookingReference, version + 1);
    }
}
