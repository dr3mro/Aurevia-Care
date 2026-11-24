package com.aurevia.orderentry.place;

import java.time.Instant;

/**
 * An immutable placed clinical order (LAB-01, RAD-01, XCH-01, XCH-02).
 *
 * <p>Placement snapshots the final draft content, the chosen delivery mode and
 * the authenticated placer. Once placed, an order is never edited in place: a
 * retry with the same idempotency key returns this same record, and later
 * changes go through the cancel or amend slices that create linked revisions.
 * Every instance carries its tenant so a read from another organization cannot
 * observe it.</p>
 *
 * @param organizationId owning tenant
 * @param id             stable tenant-local placed-order identifier
 * @param draftId        draft this order was placed from
 * @param version        immutable revision, always one at placement
 * @param request        the snapshotted order content including delivery mode
 * @param payload        canonical content fingerprint bound to the idempotency key
 * @param placedBy       authenticated clinician that placed the order
 * @param placedAt       time of placement
 * @param idempotencyKey key that made this placement idempotent
 */
public record PlacedOrder(String organizationId, String id, String draftId, int version,
                          PlacedOrderRequest request, String payload, String placedBy, Instant placedAt,
                          String idempotencyKey) {
}
