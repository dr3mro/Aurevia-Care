package com.aurevia.orderentry.delivery;

import java.time.Instant;

/**
 * Evidence for a manual order handover when the partner has no approved interface (PRD-02).
 *
 * <p>Delivery and verification are separate facts: the deliverer hands the order
 * over at {@code deliveredAt}, a different authorized verifier confirms it at
 * {@code verifiedAt} against a retained artifact for a recorded purpose. The
 * verifier must not be the deliverer, so one person cannot attest both sides of
 * the handover.</p>
 *
 * @param deliveredBy         actor who physically delivered the order
 * @param deliveredAt         time of the handover
 * @param verifiedBy          distinct authorized actor who verified the handover
 * @param verifiedAt          time of verification
 * @param verificationArtifact opaque reference or digest of the retained evidence
 * @param purpose             permitted purpose of the disclosure
 */
public record ManualDelivery(String deliveredBy, Instant deliveredAt, String verifiedBy,
                             Instant verifiedAt, String verificationArtifact, String purpose) {
}
