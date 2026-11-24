package com.aurevia.config.rosters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.config.queues.OperationalQueueItem;
import com.aurevia.config.queues.OperationalWorkState;

/**
 * ADM-02 missing-coverage queue acceptance with synthetic roster facts only.
 *
 * <p>A roster is a coverage gap when it has no named owner or no named backup.
 * The query is tenant scoped and never reads another organization's rosters.</p>
 */
class MissingCoverageQueueTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final Instant CREATED = Instant.parse("2026-11-21T09:00:00Z");

    @Test
    void aRosterWithNoOwnerAppearsAsAnOpenItem() {
        List<OperationalQueueItem> items = MissingCoverageQueue.query(ORG, List.of(
                roster(ORG, "unit-1", null, "backup-1", CREATED)));

        assertEquals(1, items.size());
        OperationalQueueItem item = items.get(0);
        assertEquals(ORG, item.organizationId());
        assertEquals("unit-1", item.id());
        assertEquals("missing_coverage", item.queue());
        assertEquals(CREATED, item.createdAt());
        assertEquals(OperationalWorkState.OPEN, item.state());
        assertTrue(item.actions().isEmpty());
    }

    @Test
    void blankOwnerAndBlankBackupAreCoverageGaps() {
        List<OperationalQueueItem> items = MissingCoverageQueue.query(ORG, List.of(
                roster(ORG, "blank-owner", "  ", "backup-1", CREATED),
                roster(ORG, "blank-backup", "owner-1", "\t", CREATED)));

        assertEquals(List.of("blank-owner", "blank-backup"), ids(items));
    }

    @Test
    void nullBackupIsACoverageGap() {
        List<OperationalQueueItem> items = MissingCoverageQueue.query(ORG, List.of(
                roster(ORG, "null-backup", "owner-1", null, CREATED)));

        assertEquals(List.of("null-backup"), ids(items));
    }

    @Test
    void aFullyStaffedRosterLeavesTheQueue() {
        List<OperationalQueueItem> items = MissingCoverageQueue.query(ORG, List.of(
                roster(ORG, "staffed", "owner-1", "backup-1", CREATED)));

        assertTrue(items.isEmpty());
    }

    @Test
    void tenantsNeverSeeEachOthersCoverageGaps() {
        List<CoverageRosterFact> rosters = List.of(
                roster(ORG, "same-unit", null, "backup-1", CREATED),
                roster(OTHER_ORG, "same-unit", "owner-2", "backup-2", CREATED),
                roster(OTHER_ORG, "other-gap", "  ", "backup-2", CREATED));

        List<OperationalQueueItem> orgItems = MissingCoverageQueue.query(ORG, rosters);
        List<OperationalQueueItem> otherItems = MissingCoverageQueue.query(OTHER_ORG, rosters);

        assertEquals(List.of("same-unit"), ids(orgItems));
        assertEquals(List.of("other-gap"), ids(otherItems));
        assertEquals(ORG, orgItems.get(0).organizationId());
        assertEquals(OTHER_ORG, otherItems.get(0).organizationId());
    }

    @Test
    void anEmptyRosterListProducesAnEmptyQueue() {
        assertTrue(MissingCoverageQueue.query(ORG, List.of()).isEmpty());
    }

    @Test
    void theResultIsFrozenAgainstCallerMutation() {
        List<CoverageRosterFact> rosters = new ArrayList<>();
        rosters.add(roster(ORG, "unit-1", null, "backup-1", CREATED));

        List<OperationalQueueItem> items = MissingCoverageQueue.query(ORG, rosters);
        rosters.clear();

        assertEquals(1, items.size());
        assertThrows(UnsupportedOperationException.class, () -> items.clear());
    }

    private static List<String> ids(List<OperationalQueueItem> items) {
        return items.stream().map(OperationalQueueItem::id).toList();
    }

    private static CoverageRosterFact roster(String organizationId, String unitId, String owner,
                                             String backup, Instant createdAt) {
        return new CoverageRosterFact(organizationId, unitId, owner, backup, createdAt);
    }
}
