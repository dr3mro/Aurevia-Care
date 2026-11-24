package com.aurevia.orderentry.delivery;

/**
 * Opaque instruction handed to an approved partner channel (XCH-01, XCH-02).
 *
 * <p>The submission identifier is stable for the lifetime of the order so a
 * bounded retry is deduplicated by the partner as the same submission. The
 * payload digest identifies the released snapshot; no clinical content or
 * credential is carried on this request.</p>
 *
 * @param organizationId owning tenant
 * @param orderId        tenant-local placed-order identifier
 * @param submissionId   stable submission identifier reused on retry
 * @param partnerCode    approved partner interface key
 * @param payloadDigest  digest of the released snapshot, never the payload
 */
public record SendRequest(String organizationId, String orderId, String submissionId,
                          String partnerCode, String payloadDigest) {
}
