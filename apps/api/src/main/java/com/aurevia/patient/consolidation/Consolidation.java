package com.aurevia.patient.consolidation;

/**
 * An immutable consolidation event and its optional correction (IAM-04).
 *
 * <p>Version 1 records the approval by the second reviewer. A later version with
 * status {@code CORRECTED} is appended by the audited support workflow; the prior
 * event, its author and its reason are never changed.</p>
 *
 * @param id               opaque consolidation identifier
 * @param reviewId         review ticket that was decided
 * @param survivorId       patient retaining chart visibility
 * @param duplicateId      patient whose visibility was moved or restored
 * @param version          append-only version within the review
 * @param status           {@code CONSOLIDATED} or {@code CORRECTED}
 * @param approverSubject  authenticated actor who approved or corrected
 * @param openedBySubject  original reviewer, retained as provenance
 * @param reason           reason for the consolidation
 * @param correctionReason reason for a correction, or {@code null}
 * @return this record
 */
public record Consolidation(String id, String reviewId, String survivorId, String duplicateId,
                            int version, String status, String approverSubject, String openedBySubject,
                            String reason, String correctionReason) {
}
