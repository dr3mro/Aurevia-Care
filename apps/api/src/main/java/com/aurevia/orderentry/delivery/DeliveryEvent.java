package com.aurevia.orderentry.delivery;

import java.time.Instant;

/**
 * One immutable, append-only delivery fact (XCH-01, XCH-02, PRD-02).
 *
 * <p>Only opaque identifiers, wire keys and server timestamps are recorded; no
 * clinical payload or credential is ever stored in the delivery ledger. Each
 * fact binds the acting subject, the placed-order revision it describes and the
 * instant it occurred. Manual-delivery facts additionally carry the distinct
 * verifier and verification time.</p>
 *
 * @param id           server-generated immutable event identifier
 * @param organizationId owning tenant
 * @param orderId      tenant-local placed-order identifier
 * @param orderVersion placed-order revision the fact describes
 * @param kind         delivery fact kind
 * @param attempt      one-based send attempt, or zero for a non-send fact
 * @param actor        authenticated subject that caused the fact
 * @param detail       non-sensitive detail, reason or evidence reference
 * @param verifiedBy   distinct manual verifier, or {@code null}
 * @param verifiedAt   manual verification instant, or {@code null}
 * @param occurredAt   server instant of the fact
 */
public record DeliveryEvent(String id, String organizationId, String orderId, int orderVersion,
                            DeliveryEventKind kind, int attempt, String actor, String detail,
                            String verifiedBy, Instant verifiedAt, Instant occurredAt) {
}
