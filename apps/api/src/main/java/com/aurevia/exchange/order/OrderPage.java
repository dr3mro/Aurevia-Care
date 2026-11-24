package com.aurevia.exchange.order;

import java.util.List;

/**
 * One cursor-paginated page of released orders in the active organization.
 *
 * <p>The {@code nextCursor} is the opaque {@code <createdAt>:<id>} of the last
 * returned row, or {@code null} when this is the final page. The list is frozen
 * so a caller cannot mutate a projection that was produced inside a protected
 * transaction.</p>
 *
 * @return this record
 */
public record OrderPage(List<OrderView> items, String nextCursor) {

    /**
     * Freeze the page items so callers cannot mutate the returned projection.
     *
     * @throws NullPointerException when items is null
     */
    public OrderPage {
        items = List.copyOf(items);
    }

    /**
     * Return the immutable order list in creation-time descending order.
     *
     * @return orders on this page
     */
    @Override
    public List<OrderView> items() {
        return List.copyOf(items);
    }
}
