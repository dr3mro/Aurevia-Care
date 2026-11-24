package com.aurevia.it.exchangetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.task.ClinicalReviewState;
import com.aurevia.exchange.task.ExchangeCommand;
import com.aurevia.exchange.task.ExchangeStateMachine;
import com.aurevia.exchange.task.ExchangeTask;
import com.aurevia.exchange.task.TransmissionState;
import com.aurevia.exchange.task.WorkflowState;

/** Pure XCH-02 transition logic: matrices, revision binding and dimension independence. */
class ExchangeStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private static ExchangeTask initial() {
        return new ExchangeTask("task-1", 1, "LOCAL-1", WorkflowState.REQUESTED,
                TransmissionState.PENDING, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW.minusSeconds(60));
    }

    @Test
    void workflowAdvanceOnlyChangesTheWorkflowDimension() {
        ExchangeTask next = ExchangeStateMachine.next(initial(), ExchangeCommand.ACCEPT, NOW);

        assertEquals(2, next.version());
        assertEquals(WorkflowState.ACCEPTED, next.workflowState());
        assertEquals(TransmissionState.PENDING, next.transmissionState());
        assertEquals(ClinicalReviewState.UNREVIEWED, next.clinicalReviewState());
        assertNull(next.reviewedBy());
        assertNull(next.reviewedAt());
        assertNull(next.reviewedRevision());
        assertSame(initial().workflowState(), WorkflowState.REQUESTED);
    }

    @Test
    void aTransmissionAcknowledgmentNeverMutatesClinicalReview() {
        ExchangeTask sent = ExchangeStateMachine.next(initial(), ExchangeCommand.SEND, NOW);
        ExchangeTask delivered = ExchangeStateMachine.next(sent, ExchangeCommand.DELIVER, NOW);
        ExchangeTask failedFromSent = ExchangeStateMachine.next(sent, ExchangeCommand.FAIL, NOW);

        for (ExchangeTask result : new ExchangeTask[] {sent, delivered, failedFromSent}) {
            assertEquals(ClinicalReviewState.UNREVIEWED, result.clinicalReviewState());
            assertNull(result.reviewedBy());
            assertNull(result.reviewedAt());
            assertNull(result.reviewedRevision());
            assertEquals(WorkflowState.REQUESTED, result.workflowState());
        }
        assertEquals(TransmissionState.SENT, sent.transmissionState());
        assertEquals(TransmissionState.DELIVERED, delivered.transmissionState());
        assertEquals(TransmissionState.FAILED, failedFromSent.transmissionState());
    }

    @Test
    void humanAcknowledgmentBindsTimestampAndRevisionAndAdvancesVersion() {
        ExchangeTask acknowledged = ExchangeStateMachine.next(initial(), ExchangeCommand.ACKNOWLEDGE, NOW);

        assertEquals(ClinicalReviewState.ACKNOWLEDGED, acknowledged.clinicalReviewState());
        assertEquals(NOW, acknowledged.reviewedAt());
        assertEquals(1, acknowledged.reviewedRevision());
        assertNull(acknowledged.reviewedBy());
        assertEquals(WorkflowState.REQUESTED, acknowledged.workflowState());
        assertEquals(TransmissionState.PENDING, acknowledged.transmissionState());
    }

    @Test
    void reviewChainRequiresPriorHumanAcknowledgment() {
        ExchangeTask acknowledged = ExchangeStateMachine.next(initial(), ExchangeCommand.ACKNOWLEDGE, NOW);
        ExchangeTask actionPending = ExchangeStateMachine.next(acknowledged, ExchangeCommand.REQUEST_ACTION, NOW);
        ExchangeTask resolved = ExchangeStateMachine.next(actionPending, ExchangeCommand.RESOLVE, NOW);

        assertEquals(ClinicalReviewState.ACTION_PENDING, actionPending.clinicalReviewState());
        assertEquals(2, actionPending.reviewedRevision());
        assertEquals(ClinicalReviewState.RESOLVED, resolved.clinicalReviewState());
        assertEquals(3, resolved.reviewedRevision());
        assertEquals(WorkflowState.REQUESTED, resolved.workflowState());
        assertEquals(TransmissionState.PENDING, resolved.transmissionState());
    }

    @Test
    void illegalTransitionsAreConflictsWithoutMutation() {
        assertConflict(() -> ExchangeStateMachine.next(initial(), ExchangeCommand.COMPLETE, NOW));
        assertConflict(() -> ExchangeStateMachine.next(initial(), ExchangeCommand.DELIVER, NOW));
        assertConflict(() -> ExchangeStateMachine.next(initial(), ExchangeCommand.RESOLVE, NOW));

        ExchangeTask resolved = ExchangeStateMachine.next(
                ExchangeStateMachine.next(ExchangeStateMachine.next(initial(),
                        ExchangeCommand.ACKNOWLEDGE, NOW), ExchangeCommand.REQUEST_ACTION, NOW),
                ExchangeCommand.RESOLVE, NOW);
        assertConflict(() -> ExchangeStateMachine.next(resolved, ExchangeCommand.ACKNOWLEDGE, NOW));
        assertConflict(() -> ExchangeStateMachine.next(resolved, ExchangeCommand.DELIVER, NOW));
        assertConflict(() -> ExchangeStateMachine.next(resolved, ExchangeCommand.COMPLETE, NOW));
    }

    @Test
    void duplicatedAcknowledgmentOfTheSameRevisionIsAConflict() {
        ExchangeTask acknowledged = ExchangeStateMachine.next(initial(), ExchangeCommand.ACKNOWLEDGE, NOW);
        assertConflict(() -> ExchangeStateMachine.next(acknowledged, ExchangeCommand.ACKNOWLEDGE, NOW));
    }

    private static void assertConflict(Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(409, failure.status());
        assertEquals("INVALID_TRANSITION", failure.code());
    }
}
