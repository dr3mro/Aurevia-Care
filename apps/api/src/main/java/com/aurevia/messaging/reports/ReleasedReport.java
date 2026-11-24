package com.aurevia.messaging.reports;

/**
 * One immutable revision of a report that has been considered for patient release (PAT-02, XCH-06).
 *
 * <p>A correction is a new revision with a
 * {@link com.aurevia.messaging.reports.ReleasedReport.CorrectionContext} that points at the revision it
 * replaces. The earlier revision is retained for provenance but is never the "current" release.
 * Withdrawn and held revisions are retained for the same reason and are never served to the patient.</p>
 *
 * @param id                  stable report identifier, shared by all revisions
 * @param revision            monotonically increasing revision number
 * @param patientId           patient whose chart owns the report
 * @param organizationId      tenant that owns the report (ARC-02)
 * @param issuingOrganization organization shown to the patient as the issuer
 * @param effectiveDate       ISO civil date from which the revision may be released
 * @param language            language of the rendered document ({@code en} or {@code ar})
 * @param documentId          opaque identifier of the immutable rendered artifact
 * @param checksum            checksum of the rendered artifact
 * @param status              current release status of this revision
 * @param correction          correction context, or {@code null} for an original revision
 * @return this record
 */
public record ReleasedReport(String id, int revision, String patientId, String organizationId,
                             String issuingOrganization, String effectiveDate, String language,
                             String documentId, String checksum, Status status,
                             CorrectionContext correction) {

    /** Release lifecycle visible to the patient access service (XCH-06). */
    public enum Status {
        /** Released and currently eligible for patient access. */
        RELEASED,
        /** Withdrawn; future downloads stop, history is retained. */
        WITHDRAWN,
        /** Held pending policy approval; never eligible for patient access. */
        HELD
    }

    /**
     * Correction provenance displayed with a released revision (PAT-02, XCH-04).
     *
     * @param supersedesRevision revision replaced by this correction
     * @param reason             non-sensitive correction reason shown to the patient
 * @return this record
 */
public record CorrectionContext(int supersedesRevision, String reason) {
    }
}
