package com.aurevia.radiology;

import java.time.Instant;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.RadTypes.StudyLinkReviewStatus;

/**
 * One immutable study-link review revision raised when an external study cannot be associated (RAD-01).
 *
 * <p>The review keeps both the labels supplied by the caller and the labels
 * expected from the order, so a reviewer can compare them without guesswork.
 * No image pixel or clinical narrative is stored: only identifiers and the
 * reviewer's decision. A resolution is always a new revision created by
 * {@link com.aurevia.radiology.StudyLinkReview#resolve(String, String, Instant)};
 * the earlier revision is never edited.</p>
 *
 * @return this record
 */
public record StudyLinkReview(String organizationId, String orderId, String studyInstanceUid,
                              String attemptedPatientId, String attemptedAccession,
                              String expectedPatientId, String expectedAccession,
                              StudyLinkReviewStatus status, String reviewer, String resolution,
                              Instant openedAt, Instant resolvedAt) {

    /**
     * Validate the identifiers a reviewer needs to route and audit this review.
     *
     * @throws ApiException when tenant, order, study, status or time is missing
     */
    public StudyLinkReview {
        if (organizationId == null || organizationId.isBlank()) {
            throw invalid();
        }
        if (orderId == null || orderId.isBlank()) {
            throw invalid();
        }
        if (studyInstanceUid == null || studyInstanceUid.isBlank()) {
            throw invalid();
        }
        if (status == null) {
            throw invalid();
        }
        if (openedAt == null) {
            throw invalid();
        }
    }

    /**
     * Create the resolved revision of this review, leaving the original intact.
     *
     * @param reviewer   authenticated subject that reviewed the mismatch
     * @param resolution bounded decision text recorded for the audit trail
     * @param resolvedAt instant the decision was recorded
     * @return a new resolved revision of this review
     */
    public StudyLinkReview resolve(String reviewer, String resolution, Instant resolvedAt) {
        return new StudyLinkReview(organizationId, orderId, studyInstanceUid, attemptedPatientId,
                attemptedAccession, expectedPatientId, expectedAccession,
                StudyLinkReviewStatus.RESOLVED, reviewer, resolution, openedAt, resolvedAt);
    }

    private static ApiException invalid() {
        return new ApiException(422, "INVALID_STUDY_LINK_REVIEW", "invalid study-link review");
    }
}
