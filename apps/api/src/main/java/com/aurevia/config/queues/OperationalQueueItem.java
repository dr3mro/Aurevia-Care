package com.aurevia.config.queues;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.aurevia.api.ApiException;

/**
 * Immutable ADM-02 work item derived from one tenant-scoped operational queue.
 *
 * <p>Queues are projections of source facts, not stored queues: an item is
 * re-derived on every read, so a signed encounter or a fully staffed roster
 * leaves the queue without a deletion. An operator action returns a new item and
 * appends to the action history; the original value is never mutated. The
 * organization id is carried on every item so a projection can never be merged
 * across tenants.</p>
 *
 * @param organizationId owning tenant
 * @param id             local item identifier (encounter or unit)
 * @param queue          stable queue name from the ADM-02 catalog
 * @param createdAt      instant the source fact was first recorded
 * @param state          current lifecycle state
 * @param owner          named owner, or {@code null} while open
 * @param reason         reason for the latest action, or {@code null}
 * @param actions        append-only operator action history
 * @return the immutable item
 */
public record OperationalQueueItem(String organizationId, String id, String queue, Instant createdAt,
                                   OperationalWorkState state, String owner, String reason,
                                   List<QueueAction> actions) {

    /**
     * Freeze the action history.
     */
    public OperationalQueueItem {
        actions = List.copyOf(actions);
    }

    /**
     * Return the frozen action history.
     *
     * @return immutable list of actions
     */
    @Override
    public List<QueueAction> actions() {
        return List.copyOf(actions);
    }

    /**
     * Create an open item from a queue fact.
     *
     * @param organizationId owning tenant
     * @param id             local encounter or unit identifier
     * @param queue          stable queue name
     * @param createdAt      instant the source fact was recorded
     * @return an open item with no owner, reason or actions
     */
    public static OperationalQueueItem open(String organizationId, String id, String queue, Instant createdAt) {
        return new OperationalQueueItem(organizationId, id, queue, createdAt,
                OperationalWorkState.OPEN, null, null, List.of());
    }

    /**
     * Assign the item to a named owner.
     *
     * @param actor  acting operator
     * @param owner  named owner, non-empty
     * @param reason non-empty operator reason
     * @param at     instant of the action, not before the item was created
     * @return a new assigned item with the action appended
     * @throws ApiException on a blank actor, owner or reason, a resolved item
     *                      or an invalid action instant
     */
    public OperationalQueueItem assign(String actor, String owner, String reason, Instant at) {
        requireText(actor, "ACTOR_REQUIRED", "an actor is required");
        requireText(owner, "OWNER_REQUIRED", "an owner is required");
        requireText(reason, "REASON_REQUIRED", "a reason is required");
        requireActionable(at);
        List<QueueAction> next = new ArrayList<>(actions);
        next.add(new QueueAction("assign", actor, reason, at));
        return new OperationalQueueItem(organizationId, id, queue, createdAt,
                OperationalWorkState.ASSIGNED, owner, reason, next);
    }

    /**
     * Resolve the item with an outcome reason.
     *
     * @param actor  acting operator
     * @param reason non-empty outcome reason
     * @param at     instant of the action, not before the item was created
     * @return a new resolved item with the action appended
     * @throws ApiException on a blank actor or reason, a resolved item or an
     *                      invalid action instant
     */
    public OperationalQueueItem resolve(String actor, String reason, Instant at) {
        requireText(actor, "ACTOR_REQUIRED", "an actor is required");
        requireText(reason, "REASON_REQUIRED", "a reason is required");
        requireActionable(at);
        List<QueueAction> next = new ArrayList<>(actions);
        next.add(new QueueAction("resolve", actor, reason, at));
        return new OperationalQueueItem(organizationId, id, queue, createdAt,
                OperationalWorkState.RESOLVED, owner, reason, next);
    }

    private void requireActionable(Instant at) {
        if (state == OperationalWorkState.RESOLVED) {
            throw new ApiException(409, "INVALID_TRANSITION", "a resolved item cannot be actioned again");
        }
        if (at == null || at.isBefore(createdAt)) {
            throw new ApiException(422, "INVALID_ACTION_TIME", "an action cannot predate the work item");
        }
    }

    private static void requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, code, message);
        }
    }
}
