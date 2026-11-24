package com.aurevia.orderentry.delivery;

/**
 * An approved outbound channel to a delivery partner (PRD-02).
 *
 * <p>Only channels registered in the
 * {@link com.aurevia.orderentry.delivery.ApprovedChannelRegistry}
 * may be used. Implementations translate the opaque
 * {@link com.aurevia.orderentry.delivery.SendRequest} into the
 * partner-specific protocol and report a three-valued outcome so an unknown
 * result is never treated as a failure that may be retried blindly.</p>
 */
@FunctionalInterface
public interface PartnerChannel {

    /**
     * Hand one submission to the partner.
     *
     * @param request opaque submission instruction
     * @return the reported outcome, never {@code null}
     */
    SendOutcome send(SendRequest request);
}
