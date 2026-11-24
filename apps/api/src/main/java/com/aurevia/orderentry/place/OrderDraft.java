package com.aurevia.orderentry.place;

import java.time.Instant;

/**
 * An editable, versioned draft order before placement (XCH-01, XCH-02).
 *
 * <p>A draft is the only mutable phase of an order. Each edit returns a new
 * immutable revision sharing the tenant-local draft identifier, so a stale
 * {@code If-Match} version can be refused without losing the saved content.
 * Placement consumes the draft; a placed order is a separate immutable record
 * and can only change through the later cancel or amend slices.</p>
 *
 * @param organizationId owning tenant
 * @param id             stable tenant-local draft identifier
 * @param version        monotonically increasing draft revision
 * @param request        the current order content
 * @param createdBy      authenticated subject that saved the draft
 * @param createdAt      time the draft was first saved
 */
public record OrderDraft(String organizationId, String id, int version, PlacedOrderRequest request,
                         String createdBy, Instant createdAt) {

    /**
     * Return the next revision of this draft with new content.
     *
     * @param revisedRequest validated replacement content
     * @return a new draft revision with an incremented version
     */
    public OrderDraft revised(PlacedOrderRequest revisedRequest) {
        return new OrderDraft(organizationId, id, version + 1, revisedRequest, createdBy, createdAt);
    }
}
