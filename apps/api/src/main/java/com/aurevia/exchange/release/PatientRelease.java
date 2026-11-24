package com.aurevia.exchange.release;

import java.time.Clock;
import java.time.Instant;

/**
 * Immutable projection of one patient release authorization (XCH-06, PAT-02).
 *
 * <p>The release references an immutable report revision by its opaque identifier
 * and version, the issuing organization and the policy version that governed the
 * decision. The current row is mutable only for state and version; every decision
 * is recorded separately in {@link com.aurevia.exchange.release.ReleaseEvent} and the release notification outbox.</p>
 *
 * @param id                    opaque tenant-local release identifier
 * @param organizationId        owning tenant
 * @param patientId              patient whose chart the revision belongs to
 * @param revisionId             issuing organization's immutable report revision id
 * @param revisionVersion        version of the referenced revision
 * @param issuingOrganizationId organization that issued the report revision
 * @param policyVersion          approved release policy version applied
 * @param kind                   immediate, timed or hold policy
 * @param state                  current lifecycle state
 * @param effectiveFrom          instant from which the release may be visible
 * @param holdReason             non-blank reason when {@code kind} is HOLD
 * @param holdExpiresAt          review deadline for a hold
 * @param reviewedBy             user who lifted a hold
 * @param reviewedAt             instant a hold was lifted
 * @param supersedesId           release replaced by this one after a correction
 * @param version                optimistic-concurrency version
 * @param createdBy              user who authorized the release
 * @param createdAt              instant the release was authorized
 * @return this record
 */
public record PatientRelease(
        String id,
        String organizationId,
        String patientId,
        String revisionId,
        int revisionVersion,
        String issuingOrganizationId,
        int policyVersion,
        ReleaseKind kind,
        ReleaseState state,
        Instant effectiveFrom,
        String holdReason,
        Instant holdExpiresAt,
        String reviewedBy,
        Instant reviewedAt,
        String supersedesId,
        int version,
        String createdBy,
        Instant createdAt) {

    /**
     * Decide current patient access using the injected clock, never the client clock.
     *
     * @param clock shared UTC clock
     * @return {@code true} when this revision may be shown or downloaded now
     */
    public boolean visibleNow(Clock clock) {
        return state.visibleAt(effectiveFrom, clock.instant());
    }
}
