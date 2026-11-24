package com.aurevia.orderentry.place;

import com.aurevia.api.ApiException;

/**
 * One catalog item captured on an order at placement time (LAB-01, RAD-01, XCH-01).
 *
 * <p>An order stores the stable local catalog item identifier and the exact
 * version it selected, so a later retirement or new catalog version never
 * rewrites what was ordered. The local identifiers are opaque; tenant and
 * facility ownership are supplied by the enclosing request and verified by the
 * placement service, never inferred from this value.</p>
 *
 * @param itemId  stable tenant-local orderable item identifier
 * @param version catalog version captured with the item
 */
public record OrderSelection(String itemId, int version) {

    /**
     * Validate the captured catalog reference at the trust boundary.
     *
     * @throws ApiException {@code 422} when the item identifier is missing or the version is not positive
     */
    public OrderSelection {
        if (itemId == null || itemId.isBlank()) {
            throw new ApiException(422, "INVALID_ORDER_ITEM", "itemId is required");
        }
        if (version < 1) {
            throw new ApiException(422, "INVALID_ORDER_ITEM", "item version must be positive");
        }
    }
}
