package com.aurevia.config.rosters;

import java.time.Instant;

/**
 * One tenant-scoped coverage roster fact used to derive ADM-02 missing-coverage items.
 *
 * <p>This is a read-only projection input: the authoritative roster is configured
 * by the tenant administrator. A synthetic fixture can be used in tests without
 * exposing any real staff or patient identifier.</p>
 *
 * @param organizationId owning tenant
 * @param unitId         unit whose roster is described
 * @param ownerSubject   named primary owner, or {@code null} when missing
 * @param backupSubject  named backup owner, or {@code null} when missing
 * @param createdAt      instant the roster fact was recorded
 * @return the immutable fact
 */
public record CoverageRosterFact(String organizationId, String unitId, String ownerSubject,
                                 String backupSubject, Instant createdAt) {
}
