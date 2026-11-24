package com.aurevia.exchange.release;

import java.time.Instant;

/**
 * Append-only decision record for one release authorization (XCH-06, OPS-02).
 *
 * <p>Withdrawal and correction never erase the original authorization; each
 * transition appends a new event inside the same transaction as the state change.</p>
 *
 * @param id         opaque tenant-local event identifier
 * @param releaseId  release the event describes
 * @param action     decision action key
 * @param fromState  state before the decision, or {@code null} on authorization
 * @param toState    state after the decision
 * @param reason     non-sensitive reason supplied for withdrawal, correction or review
 * @param actorId    authenticated actor, derived server-side
 * @param occurredAt instant the decision was committed
 * @return this record
 */
public record ReleaseEvent(
        String id,
        String releaseId,
        String action,
        String fromState,
        String toState,
        String reason,
        String actorId,
        Instant occurredAt) {
}
