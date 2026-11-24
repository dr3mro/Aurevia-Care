package com.aurevia.patient.identity;

/**
 * Stored state of a single-use, expiring invitation (IAM-05).
 *
 * <p>Only the token hash is persisted; the raw token is returned to the caller
 * once at issue time. The database owns expiry and single-use consumption.</p>
 *
 * @param id             opaque invitation identifier
 * @param accountSubject account the invitation is bound to
 * @param chartId        chart whose link the invitation authorizes
 * @param purpose        bounded purpose key (ENROLL, LINK or REVERIFY)
 * @param expiresAt      ISO instant after which the invitation is unusable
 * @param consumed       {@code true} once the invitation has been used
 * @return this record
 */
public record InvitationRecord(String id, String accountSubject, String chartId, String purpose,
                               String expiresAt, boolean consumed) {
}
