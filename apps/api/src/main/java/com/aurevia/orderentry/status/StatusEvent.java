package com.aurevia.orderentry.status;

import java.time.Instant;

/**
 * One immutable, append-only fact in an order's status trail (XCH-03).
 *
 * <p>Facts carry only opaque identifiers, a recorded detail and server
 * timestamps; no clinical payload or credential is stored. The revision binds
 * each fact to the exact order revision it describes.</p>
 *
 * @param id             server-generated immutable fact identifier
 * @param organizationId owning tenant
 * @param orderId        tenant-local order identifier
 * @param revision       order revision this fact describes
 * @param kind           fact kind
 * @param detail         non-sensitive detail, reason or evidence reference
 * @param actor          authenticated subject that caused the fact
 * @param occurredAt     server instant of the fact
 */
public record StatusEvent(String id, String organizationId, String orderId, int revision,
                          StatusEventKind kind, String detail, String actor, Instant occurredAt) {
}
