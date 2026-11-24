package com.aurevia.orderentry.catalog;

/**
 * Whether an orderable catalog version may still be selected for a new order (ADM-01, XCH-01).
 *
 * <p>Retirement removes an item from search and from the orderable resolution
 * path, but never erases the immutable version: an order placed before
 * retirement continues to show the exact version it used.</p>
 */
public enum OrderableStatus {

    /** Available to be selected for a new order while its effective window is open. */
    ACTIVE,
    /** Withdrawn from new orders; retained so historical orders can still render it. */
    RETIRED
}
