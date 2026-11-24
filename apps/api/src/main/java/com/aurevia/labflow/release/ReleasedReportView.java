package com.aurevia.labflow.release;

import java.time.Clock;
import java.time.Instant;

import com.aurevia.exchange.release.PatientRelease;

/**
 * Non-sensitive projection of a released laboratory report for a clinician or patient (PAT-02).
 *
 * <p>Carries the immutable revision reference, issuing organization and effective date, the
 * current patient visibility decision from the injected clock, and the correction context or
 * withdrawal notice that PAT-02 requires a reader to see.</p>
 *
 * @param releaseId             tenant-local release identifier
 * @param revisionId            immutable report revision identifier
 * @param revisionVersion       version of that revision
 * @param releaseState          current {@link com.aurevia.exchange.release.ReleaseState} name
 * @param issuingOrganizationId organization that issued the report revision
 * @param effectiveFrom         instant from which patient access may open
 * @param patientVisibleNow     whether the patient may see the revision at this instant
 * @param correctionContext     explanation of a superseding correction, or {@code null}
 * @param withdrawalNotice      notice shown for a withdrawn report, or {@code null}
 * @return this view
 */
public record ReleasedReportView(String releaseId, String revisionId, int revisionVersion,
                                 String releaseState, String issuingOrganizationId, Instant effectiveFrom,
                                 boolean patientVisibleNow, String correctionContext,
                                 String withdrawalNotice) {

    /**
     * Build a view from the durable release projection.
     *
     * @param release           committed release authorization
     * @param clock             shared UTC clock for the visibility decision
     * @param correctionContext correction explanation, or {@code null}
     * @param withdrawalNotice  withdrawal notice, or {@code null}
     * @return the projection
     */
    public static ReleasedReportView of(PatientRelease release, Clock clock, String correctionContext,
                                        String withdrawalNotice) {
        return new ReleasedReportView(release.id(), release.revisionId(), release.revisionVersion(),
                release.state().name(), release.issuingOrganizationId(), release.effectiveFrom(),
                release.visibleNow(clock), correctionContext, withdrawalNotice);
    }
}
