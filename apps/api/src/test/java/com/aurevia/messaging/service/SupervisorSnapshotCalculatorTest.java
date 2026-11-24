package com.aurevia.messaging.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.messaging.service.ServiceProfileContract.SupervisorSnapshot;
import com.aurevia.messaging.service.ServiceProfileContract.WorkItemFact;
import com.aurevia.messaging.service.ServiceProfileContract.WorkState;

/**
 * Unit acceptance for the derived MSG-06 supervisor counters.
 *
 * <p>Backlog, unassigned work, failed notifications and overdue responses are
 * separate facts computed from the same immutable snapshot. Resolved and closed
 * work is excluded. All fixtures are synthetic and carry no patient data.</p>
 */
class SupervisorSnapshotCalculatorTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void allCountersAreZeroForAnEmptyQueue() {
        SupervisorSnapshot result = compute(List.of());
        assertEquals(0, result.backlog());
        assertEquals(0, result.unassigned());
        assertEquals(0, result.failedNotifications());
        assertEquals(0, result.overdueResponses());
        assertEquals(NOW, result.generatedAt());
        assertEquals(ORG, result.organizationId());
        assertEquals(UNIT, result.unitId());
    }

    @Test
    void backlogExcludesResolvedAndClosedWork() {
        SupervisorSnapshot result = compute(List.of(
                item("open-1", WorkState.AWAITING_STAFF, "nurse", NOW, null, false),
                item("open-2", WorkState.AWAITING_PATIENT, "nurse", NOW, NOW, false),
                item("done", WorkState.RESOLVED, "nurse", NOW, null, false),
                item("closed", WorkState.CLOSED, "nurse", NOW, null, false)));
        assertEquals(2, result.backlog());
    }

    @Test
    void unassignedCountsNullAndBlankAssigneesOnlyOnOpenWork() {
        SupervisorSnapshot result = compute(List.of(
                item("null-assignee", WorkState.AWAITING_STAFF, null, NOW, null, false),
                item("blank-assignee", WorkState.AWAITING_STAFF, "  ", NOW, null, false),
                item("assigned", WorkState.AWAITING_STAFF, "nurse", NOW, null, false),
                item("resolved-unassigned", WorkState.RESOLVED, null, NOW, null, false)));
        assertEquals(2, result.unassigned());
    }

    @Test
    void failedNotificationsAreSurfacedRegardlessOfWorkState() {
        SupervisorSnapshot result = compute(List.of(
                item("failed", WorkState.AWAITING_STAFF, "nurse", NOW, null, true),
                item("failed-resolved", WorkState.RESOLVED, "nurse", NOW, null, true),
                item("ok", WorkState.AWAITING_STAFF, "nurse", NOW, null, false)));
        assertEquals(2, result.failedNotifications());
    }

    @Test
    void overdueUsesAnInclusiveDeadlineAndIgnoresAlreadyRespondedWork() {
        SupervisorSnapshot result = compute(List.of(
                item("exactly-due", WorkState.AWAITING_STAFF, "nurse", NOW.minusSeconds(1800), null, false),
                item("past-due", WorkState.AWAITING_STAFF, "nurse", NOW.minusSeconds(3600), null, false),
                item("not-due", WorkState.AWAITING_STAFF, "nurse", NOW.minusSeconds(60), null, false),
                item("responded", WorkState.AWAITING_STAFF, "nurse", NOW.minusSeconds(3600), NOW, false),
                item("closed-due", WorkState.CLOSED, "nurse", NOW.minusSeconds(3600), null, false)));
        assertEquals(2, result.overdueResponses());
    }

    private static SupervisorSnapshot compute(List<WorkItemFact> items) {
        return SupervisorSnapshotCalculator.compute(ORG, UNIT, 30, items, NOW);
    }

    private static WorkItemFact item(String id, WorkState state, String assignee, Instant createdAt,
                                     Instant lastStaffResponseAt, boolean notificationFailed) {
        return new WorkItemFact(ORG, id, UNIT, state, assignee, createdAt, lastStaffResponseAt, notificationFailed);
    }
}
