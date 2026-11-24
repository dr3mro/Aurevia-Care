package com.aurevia.patient.identity;

/**
 * The one-time response to issuing or re-verifying an invitation (IAM-05).
 *
 * <p>Carries the raw token, which is never stored and cannot be recovered
 * later. The expiration instant lets the patient app show the time window.</p>
 *
 * @param subject   account the invitation is bound to
 * @param chartId   chart whose link the invitation authorizes
 * @param purpose   bounded purpose key
 * @param token     one-time raw token (returned only here)
 * @param expiresAt ISO instant when the invitation expires
 * @return this record
 */
public record InvitationIssue(String subject, String chartId, String purpose, String token, String expiresAt) {
}
