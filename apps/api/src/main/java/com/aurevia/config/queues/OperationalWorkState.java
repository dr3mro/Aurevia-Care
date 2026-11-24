package com.aurevia.config.queues;

/**
 * Lifecycle of one ADM-02 operational work item.
 *
 * <p>An item is opened from a derived queue fact, may be handed to a named owner,
 * and is closed with a reason. Resolution is terminal: a resolved item is kept
 * for audit history and is never reactivated by a later assignment.</p>
 */
public enum OperationalWorkState {

    /** Newly derived item with no owner. */
    OPEN,
    /** Item with a named owner. */
    ASSIGNED,
    /** Closed item whose reason is retained. */
    RESOLVED
}
