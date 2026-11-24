package com.aurevia.patient.consolidation;

/**
 * A review ticket pairing a surviving patient with a suspected duplicate (IAM-04).
 *
 * <p>The ticket is opened by one authorized reviewer; consolidation must be
 * approved by a different reviewer. Status is folded from the append-only
 * consolidation history: {@code OPEN}, {@code CONSOLIDATED} or {@code CORRECTED}.</p>
 *
 * @param id               opaque review identifier
 * @param survivorId       effective patient selected to survive
 * @param duplicateId      patient whose chart visibility is moved
 * @param openedBySubject  authenticated subject that opened the review
 * @param version          append-only ticket version
 * @param status           folded lifecycle status
 * @return this record
 */
public record DuplicateReview(String id, String survivorId, String duplicateId,
                              String openedBySubject, int version, String status) {
}
