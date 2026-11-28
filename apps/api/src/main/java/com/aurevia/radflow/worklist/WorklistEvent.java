package com.aurevia.radflow.worklist;

import java.time.Instant;

import com.aurevia.radflow.worklist.WorklistTypes.WorklistEventType;

/**
 * One immutable fact produced by the worklist kernel.
 *
 * <p>The event carries only identifiers and the actor. No clinical narrative or
 * image data is placed in an event, so an operational fact cannot disclose a
 * protected payload. Persistence and delivery belong to a later outbox story.</p>
 *
 * @return this record
 */
public record WorklistEvent(String organizationId, WorklistEventType type, String orderId,
                            int orderVersion, String patientId, String performingSiteId,
                            String accession, String actor, String destination, Instant occurredAt) {
}
