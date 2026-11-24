package com.aurevia.scheduling.checkin;

import java.time.Instant;

/**
 * Appendable record of one worklist assignment or transfer (CLN-02).
 *
 * @param id          opaque event identifier
 * @param itemId      worklist entry the event belongs to
 * @param fromSubject previous assignee, or {@code null} on a first assignment
 * @param toSubject   new assignee
 * @param reason      recorded transfer reason, or {@code null} on a first assignment
 * @param actor       authenticated subject that performed the action
 * @param occurredAt  instant the handoff was committed
 * @return this record
 */
public record HandoffEvent(String id, String itemId, String fromSubject, String toSubject,
                           String reason, String actor, Instant occurredAt) {
}
