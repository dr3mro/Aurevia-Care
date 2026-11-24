package com.aurevia.config.rosters;

import java.util.ArrayList;
import java.util.List;

import com.aurevia.config.queues.OperationalQueueItem;

/**
 * Derives the ADM-02 {@code missing_coverage} queue from roster facts (ADM-01, XCH-05).
 *
 * <p>The queue is recalculated on read: once a roster has both a named owner and
 * a named backup it stops appearing, so no stale queue row can survive. Only the
 * active tenant's facts are read, and the tenant id is copied onto every item so
 * two tenants can hold the same local unit id without crossing.</p>
 */
public final class MissingCoverageQueue {

    /** Stable ADM-02 queue name. */
    public static final String QUEUE = "missing_coverage";

    private MissingCoverageQueue() {
    }

    /**
     * List the active tenant's coverage gaps.
     *
     * @param organizationId active tenant
     * @param rosters        roster facts available to the caller
     * @return immutable, tenant-scoped open work items, one per gap
     */
    public static List<OperationalQueueItem> query(String organizationId, List<CoverageRosterFact> rosters) {
        List<OperationalQueueItem> items = new ArrayList<>();
        for (CoverageRosterFact roster : rosters) {
            if (roster.organizationId().equals(organizationId)
                    && (isMissing(roster.ownerSubject()) || isMissing(roster.backupSubject()))) {
                items.add(OperationalQueueItem.open(organizationId, roster.unitId(), QUEUE, roster.createdAt()));
            }
        }
        return List.copyOf(items);
    }

    private static boolean isMissing(String subject) {
        return subject == null || subject.isBlank();
    }
}
