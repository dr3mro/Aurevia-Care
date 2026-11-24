package com.aurevia.orderentry.status;

import java.time.Instant;

/**
 * The single current status projection of one order (LAB-01, LAB-02, RAD-01, XCH-03).
 *
 * <p>Exactly one view exists per tenant and order. A cancel request after
 * acceptance is a pending fact on the same status rather than a separate
 * status: the order stays accepted until the partner answers, which keeps the
 * seven-status vocabulary intact while still requiring a recipient response.
 * All fields are immutable values, so the view can be returned directly without
 * exposing mutable state.</p>
 *
 * @param organizationId owning tenant
 * @param orderId        tenant-local order identifier
 * @param revision       current linked order revision, incremented by amend
 * @param status         the one current order status
 * @param updatedAt      server instant of the last transition
 * @param cancelReason   recorded cancel or cancel-request reason, or {@code null}
 * @param cancelRequested whether a post-acceptance cancellation awaits partner response
 * @param linkedResult   the linked result, or {@code null} before a result arrives
 */
public record OrderStatusView(String organizationId, String orderId, int revision,
                              OrderStatus status, Instant updatedAt, String cancelReason,
                              boolean cancelRequested, LinkedResult linkedResult) {
}
