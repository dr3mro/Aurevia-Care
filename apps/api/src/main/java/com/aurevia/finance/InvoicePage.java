package com.aurevia.finance;

import java.util.List;

/**
 * One cursor-paginated page of finance invoices in the active organization (ARC-03).
 *
 * <p>The {@code nextCursor} is the opaque {@code <createdAt>:<id>} of the last
 * returned row, or {@code null} when this is the final page. List rows carry no
 * item or ledger history: the single-record read keeps ownership of those
 * immutable trails, so a page stays bounded. The list is frozen so a caller
 * cannot mutate a projection produced inside a protected transaction.</p>
 *
 * @return this record
 */
public record InvoicePage(List<FinanceContract.View> items, String nextCursor) {

    /**
     * Freeze the page items so callers cannot mutate the returned projection.
     *
     * @throws NullPointerException when items is null
     */
    public InvoicePage {
        items = List.copyOf(items);
    }

    /**
     * Return the immutable invoice list in creation-time descending order.
     *
     * @return invoices on this page
     */
    @Override
    public List<FinanceContract.View> items() {
        return List.copyOf(items);
    }
}
