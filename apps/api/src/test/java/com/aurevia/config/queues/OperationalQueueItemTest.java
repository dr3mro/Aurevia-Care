package com.aurevia.config.queues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/**
 * ADM-02 work-item acceptance: assign and resolve with a named owner and reason.
 *
 * <p>Every fixture is synthetic: opaque tenant, item and actor identifiers, no
 * patient data. The item is immutable, so each action returns a new value.</p>
 */
class OperationalQueueItemTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String ACTOR = "operator-1";
    private static final Instant CREATED = Instant.parse("2026-11-21T09:00:00Z");
    private static final Instant ACTION_AT = CREATED.plusSeconds(600);

    @Test
    void openItemCarriesOnlyTenantScopeAndNoOwnerOrReason() {
        OperationalQueueItem item = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        assertEquals(ORG, item.organizationId());
        assertEquals("item-1", item.id());
        assertEquals("unsigned_encounters", item.queue());
        assertEquals(CREATED, item.createdAt());
        assertEquals(OperationalWorkState.OPEN, item.state());
        assertNull(item.owner());
        assertNull(item.reason());
        assertTrue(item.actions().isEmpty());
    }

    @Test
    void assignNamesAnOwnerAndRecordsAReasonWithoutMutatingTheOriginal() {
        OperationalQueueItem open = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        OperationalQueueItem assigned = open.assign(ACTOR, "nurse-1", "handover to day shift", ACTION_AT);

        assertEquals(OperationalWorkState.ASSIGNED, assigned.state());
        assertEquals("nurse-1", assigned.owner());
        assertEquals("handover to day shift", assigned.reason());
        assertEquals(List.of(new QueueAction("assign", ACTOR, "handover to day shift", ACTION_AT)),
                assigned.actions());
        assertEquals(OperationalWorkState.OPEN, open.state());
        assertNull(open.owner());
        assertNull(open.reason());
        assertTrue(open.actions().isEmpty());
        assertEquals(ORG, assigned.organizationId());
        assertEquals("item-1", assigned.id());
        assertEquals("unsigned_encounters", assigned.queue());
        assertEquals(CREATED, assigned.createdAt());
    }

    @Test
    void resolveRecordsAReasonAndClosesTheItemWithoutMutatingTheOriginal() {
        OperationalQueueItem open = OperationalQueueItem.open(ORG, "item-1", "missing_coverage", CREATED);

        OperationalQueueItem resolved = open.resolve(ACTOR, "roster now has a named owner", ACTION_AT);

        assertEquals(OperationalWorkState.RESOLVED, resolved.state());
        assertEquals("roster now has a named owner", resolved.reason());
        assertEquals(List.of(new QueueAction("resolve", ACTOR,
                "roster now has a named owner", ACTION_AT)), resolved.actions());
        assertEquals(OperationalWorkState.OPEN, open.state());
        assertNull(open.reason());
        assertTrue(open.actions().isEmpty());
    }

    @Test
    void anAssignedItemKeepsItsOwnerAndFullHistoryWhenResolved() {
        OperationalQueueItem assigned = OperationalQueueItem.open(ORG, "item-1", "missing_coverage", CREATED)
                .assign(ACTOR, "backup-1", "assigned to backup", ACTION_AT);

        OperationalQueueItem resolved = assigned.resolve(ACTOR, "coverage restored", ACTION_AT.plusSeconds(1));

        assertEquals("backup-1", resolved.owner());
        assertEquals(OperationalWorkState.RESOLVED, resolved.state());
        assertEquals(2, resolved.actions().size());
        assertEquals("assign", resolved.actions().get(0).type());
        assertEquals("resolve", resolved.actions().get(1).type());
        assertEquals("coverage restored", resolved.actions().get(1).reason());
        assertEquals(OperationalWorkState.ASSIGNED, assigned.state());
        assertEquals(1, assigned.actions().size());
    }

    @Test
    void assignRejectsANullOwner() {
        OperationalQueueItem item = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        ApiException failure = assertThrows(ApiException.class,
                () -> item.assign(ACTOR, null, "handover", ACTION_AT));

        assertEquals(422, failure.status());
        assertEquals("OWNER_REQUIRED", failure.code());
    }

    @Test
    void assignRejectsABlankOwner() {
        OperationalQueueItem item = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        ApiException failure = assertThrows(ApiException.class,
                () -> item.assign(ACTOR, "   ", "handover", ACTION_AT));

        assertEquals(422, failure.status());
        assertEquals("OWNER_REQUIRED", failure.code());
    }

    @Test
    void assignRejectsANullReason() {
        OperationalQueueItem item = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        ApiException failure = assertThrows(ApiException.class,
                () -> item.assign(ACTOR, "nurse-1", null, ACTION_AT));

        assertEquals(422, failure.status());
        assertEquals("REASON_REQUIRED", failure.code());
    }

    @Test
    void assignRejectsABlankReason() {
        OperationalQueueItem item = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        ApiException failure = assertThrows(ApiException.class,
                () -> item.assign(ACTOR, "nurse-1", "\t\n", ACTION_AT));

        assertEquals(422, failure.status());
        assertEquals("REASON_REQUIRED", failure.code());
    }

    @Test
    void resolveRejectsANullReason() {
        OperationalQueueItem item = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        ApiException failure = assertThrows(ApiException.class,
                () -> item.resolve(ACTOR, null, ACTION_AT));

        assertEquals(422, failure.status());
        assertEquals("REASON_REQUIRED", failure.code());
    }

    @Test
    void resolveRejectsABlankReason() {
        OperationalQueueItem item = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        ApiException failure = assertThrows(ApiException.class,
                () -> item.resolve(ACTOR, "  ", ACTION_AT));

        assertEquals(422, failure.status());
        assertEquals("REASON_REQUIRED", failure.code());
    }

    @Test
    void aBlankActorIsRejectedByBothActions() {
        OperationalQueueItem item = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        ApiException assignFailure = assertThrows(ApiException.class,
                () -> item.assign("  ", "nurse-1", "handover", ACTION_AT));
        ApiException resolveFailure = assertThrows(ApiException.class,
                () -> item.resolve(null, "done", ACTION_AT));

        assertEquals("ACTOR_REQUIRED", assignFailure.code());
        assertEquals("ACTOR_REQUIRED", resolveFailure.code());
    }

    @Test
    void aNullActionInstantIsRejectedByBothActions() {
        OperationalQueueItem item = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        ApiException assignFailure = assertThrows(ApiException.class,
                () -> item.assign(ACTOR, "nurse-1", "handover", null));
        ApiException resolveFailure = assertThrows(ApiException.class,
                () -> item.resolve(ACTOR, "done", null));

        assertEquals("INVALID_ACTION_TIME", assignFailure.code());
        assertEquals("INVALID_ACTION_TIME", resolveFailure.code());
    }

    @Test
    void anActionCannotPredateTheWorkItem() {
        OperationalQueueItem item = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED);

        ApiException failure = assertThrows(ApiException.class,
                () -> item.assign(ACTOR, "nurse-1", "handover", CREATED.minusNanos(1)));

        assertEquals(422, failure.status());
        assertEquals("INVALID_ACTION_TIME", failure.code());
    }

    @Test
    void aResolvedItemCannotBeAssignedOrResolvedAgain() {
        OperationalQueueItem resolved = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED)
                .resolve(ACTOR, "done", ACTION_AT);

        ApiException assignFailure = assertThrows(ApiException.class,
                () -> resolved.assign(ACTOR, "nurse-2", "again", ACTION_AT.plusSeconds(1)));
        ApiException resolveFailure = assertThrows(ApiException.class,
                () -> resolved.resolve(ACTOR, "again", ACTION_AT.plusSeconds(1)));

        assertEquals(409, assignFailure.status());
        assertEquals("INVALID_TRANSITION", assignFailure.code());
        assertEquals(409, resolveFailure.status());
        assertEquals("INVALID_TRANSITION", resolveFailure.code());
    }

    @Test
    void anAssignedItemCanBeAssignedToAnotherOwner() {
        OperationalQueueItem assigned = OperationalQueueItem.open(ORG, "item-1", "unsigned_encounters", CREATED)
                .assign(ACTOR, "nurse-1", "first handover", ACTION_AT);

        OperationalQueueItem reassigned = assigned.assign(ACTOR, "nurse-2", "second handover",
                ACTION_AT.plusSeconds(1));

        assertEquals("nurse-2", reassigned.owner());
        assertEquals(OperationalWorkState.ASSIGNED, reassigned.state());
        assertEquals(2, reassigned.actions().size());
        assertEquals("nurse-1", assigned.owner());
    }

    @Test
    void theActionListIsFrozenAgainstCallerMutation() {
        List<QueueAction> actions = new ArrayList<>();
        actions.add(new QueueAction("assign", ACTOR, "handover", ACTION_AT));
        OperationalQueueItem item = new OperationalQueueItem(ORG, "item-1", "unsigned_encounters", CREATED,
                OperationalWorkState.ASSIGNED, "nurse-1", "handover", actions);
        actions.clear();

        assertEquals(1, item.actions().size());
        assertThrows(UnsupportedOperationException.class,
                () -> item.actions().add(new QueueAction("resolve", ACTOR, "done", ACTION_AT)));
        assertEquals(OTHER_ORG, new OperationalQueueItem(OTHER_ORG, "item-2", "unsigned_encounters", CREATED,
                OperationalWorkState.OPEN, null, null, List.of()).organizationId());
    }
}
