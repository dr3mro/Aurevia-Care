package com.aurevia.orderentry.delivery;

import java.time.Instant;

/**
 * Current delivery projection of one placed order (XCH-01, XCH-02, PRD-02).
 *
 * <p>Exactly one projection exists per tenant and order. A retry or a switch to
 * print updates this projection in place and never creates a second order. The
 * submission identifier is minted on the first electronic attempt and reused on
 * every retry so the partner can deduplicate at-least-once delivery.</p>
 *
 * @param organizationId owning tenant
 * @param orderId        tenant-local placed-order identifier
 * @param route          channel currently chosen for delivery
 * @param status         current delivery status
 * @param submissionId   stable electronic submission identifier, or {@code null}
 * @param attempts       number of electronic send attempts made
 * @param orderVersion   placed-order revision this projection describes
 * @param updatedAt      instant of the last transition
 */
public record OrderDelivery(String organizationId, String orderId, DeliveryRoute route,
                            DeliveryStatus status, String submissionId, int attempts,
                            int orderVersion, Instant updatedAt) {
}
