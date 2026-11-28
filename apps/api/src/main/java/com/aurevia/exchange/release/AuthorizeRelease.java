package com.aurevia.exchange.release;

/**
 * Client command for a patient release authorization.
 *
 * <p>The issuing organization, author and tenant are derived from the guarded
 * request context; they are never accepted from this body.</p>
 *
 * @param revisionId      issuer-scoped immutable report revision identifier
 * @param revisionVersion version of the referenced revision
 * @param patientId       tenant-local patient identifier
 * @param policyVersion   approved release policy version applied
 * @param kind            IMMEDIATE, TIMED or HOLD
 * @param effectiveFrom   UTC instant for a timed release, otherwise optional
 * @param holdReason      required reason when the kind is HOLD
 * @param holdExpiresAt   required review deadline when the kind is HOLD
 * @return this record
 */
public record AuthorizeRelease(
        String revisionId,
        int revisionVersion,
        String patientId,
        int policyVersion,
        String kind,
        String effectiveFrom,
        String holdReason,
        String holdExpiresAt) {
}
