package com.aurevia.exchange.task;

import java.time.Instant;

/**
 * One append-only transition in the XCH-02 event ledger.
 *
 * @param id            event identifier
 * @param taskId        owning task identifier
 * @param taskVersion   task revision the event describes
 * @param channel       state channel that changed
 * @param action        audit action key of the command
 * @param fromState     previous state, or {@code null} on creation
 * @param toState       resulting state
 * @param reason        recorded reason, or {@code null}
 * @param reviewedRevision exact reviewed revision for clinical events, or {@code null}
 * @param actorId       authenticated actor, derived from the transaction context
 * @param occurredAt    event instant
 * @return this record
 */
public record ExchangeTaskEvent(String id, String taskId, int taskVersion,
                                ExchangeDimension channel, String action,
                                String fromState, String toState, String reason,
                                Integer reviewedRevision, String actorId, Instant occurredAt) {
}
