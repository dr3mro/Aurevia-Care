package com.aurevia.it.exchangetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.task.ClinicalReviewState;
import com.aurevia.exchange.task.CreateExchangeRequest;
import com.aurevia.exchange.task.ExchangeActionPolicy;
import com.aurevia.exchange.task.ExchangeCommand;
import com.aurevia.exchange.task.ExchangeDimension;
import com.aurevia.exchange.task.ExchangeTask;
import com.aurevia.exchange.task.ExchangeTaskEvent;
import com.aurevia.exchange.task.ExchangeTaskRepository;
import com.aurevia.exchange.task.ExchangeTaskService;
import com.aurevia.exchange.task.ExchangeTaskView;
import com.aurevia.exchange.task.TransmissionState;
import com.aurevia.exchange.task.WorkflowState;
import com.aurevia.tenant.TenantContext;

/** Unit coverage of XCH-02 command validation, authorization, CAS and revision binding. */
class ExchangeTaskServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private ExchangeTaskRepository repository;
    private AuditAppender audit;
    private ExchangeTaskService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, "clinician-a", "EXCHANGE", "corr-1"));
        repository = mock(ExchangeTaskRepository.class);
        audit = mock(AuditAppender.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new ExchangeTaskService(repository, new ExchangeActionPolicy(),
                new TransactionTemplate(manager), audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ExchangeTask initial() {
        return new ExchangeTask(ID, 1, "LOCAL-1", WorkflowState.REQUESTED,
                TransmissionState.PENDING, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW.minusSeconds(60));
    }

    private static ExchangeTask accepted() {
        return new ExchangeTask(ID, 2, "LOCAL-1", WorkflowState.ACCEPTED,
                TransmissionState.PENDING, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW.minusSeconds(60));
    }

    private static ExchangeTask sent() {
        return new ExchangeTask(ID, 1, "LOCAL-1", WorkflowState.REQUESTED,
                TransmissionState.SENT, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW.minusSeconds(60));
    }

    private void active(String... roles) {
        when(repository.hasActiveMembership("clinician-a")).thenReturn(true);
        when(repository.activeRoles("clinician-a")).thenReturn(List.of(roles));
    }

    @Test
    void createStoresInitialStatesAndAudits() {
        active("clinician");
        when(repository.find(any())).thenReturn(Optional.of(initial()));

        ExchangeTask created = service.create(new CreateExchangeRequest("LOCAL-1"));

        assertEquals(WorkflowState.REQUESTED, created.workflowState());
        assertEquals(TransmissionState.PENDING, created.transmissionState());
        assertEquals(ClinicalReviewState.UNREVIEWED, created.clinicalReviewState());
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(eq(ORG), id.capture(), eq("LOCAL-1"));
        verify(repository).appendEvent(id.getValue(), 1, ExchangeDimension.WORKFLOW,
                "EXCHANGE_CREATE", null, "requested", null, null);
        verify(audit).append(any(), eq("EXCHANGE_CREATE"), eq("EXCHANGE_CREATE"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(id.getValue()), eq("SUCCESS"));
    }

    @Test
    void createDeniesInactiveMembershipAndMissingRole() {
        when(repository.hasActiveMembership("clinician-a")).thenReturn(true);
        when(repository.activeRoles("clinician-a")).thenReturn(List.of("reception"));
        assertApi(403, () -> service.create(new CreateExchangeRequest("LOCAL-1")));
        verify(repository, never()).insert(any(), any(), any());

        when(repository.hasActiveMembership("clinician-a")).thenReturn(false);
        assertApi(403, () -> service.create(new CreateExchangeRequest("LOCAL-1")));
        verify(audit, org.mockito.Mockito.times(2)).append(any(), eq("EXCHANGE_CREATE"),
                eq("EXCHANGE_CREATE"), eq("EXCHANGE_TASK"), eq("EXCHANGE"), any(), eq("DENIED"));
    }

    @Test
    void duplicateOrderReferenceIsAuditedAsConflict() {
        active("clinician");
        when(repository.insert(eq(ORG), any(), eq("LOCAL-1")))
                .thenThrow(new DuplicateKeyException("duplicate order reference"));

        assertApi(409, () -> service.create(new CreateExchangeRequest("LOCAL-1")));

        verify(audit).append(any(), eq("EXCHANGE_CREATE"), eq("EXCHANGE_CREATE"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq("LOCAL-1"), eq("CONFLICT"));
        verify(repository, never()).appendEvent(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void createRejectsBlankOversizedMalformedAndNullOrderReference() {
        assertApi(422, () -> service.create(new CreateExchangeRequest("  ")));
        assertApi(422, () -> service.create(new CreateExchangeRequest("x".repeat(129))));
        assertApi(422, () -> service.create(new CreateExchangeRequest("bad ref!")));
        assertApi(422, () -> service.create(new CreateExchangeRequest(null)));
    }

    @Test
    void workflowAdvanceAppliesLegalTransitionAndAppendsHistory() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(initial()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(1);
        when(repository.find(ID)).thenReturn(Optional.of(accepted()));

        ExchangeTask result = service.execute(ID, 1, ExchangeCommand.ACCEPT, null);

        assertEquals(WorkflowState.ACCEPTED, result.workflowState());
        ArgumentCaptor<ExchangeTask> next = ArgumentCaptor.forClass(ExchangeTask.class);
        verify(repository).updateState(eq(ID), eq(1), next.capture());
        assertEquals(2, next.getValue().version());
        assertEquals(TransmissionState.PENDING, next.getValue().transmissionState());
        verify(repository).appendEvent(ID, 2, ExchangeDimension.WORKFLOW, "EXCHANGE_ACCEPT",
                "requested", "accepted", null, null);
        verify(audit).append(any(), eq("EXCHANGE_ACCEPT"), eq("EXCHANGE_ACCEPT"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("SUCCESS"));
    }

    @Test
    void transmissionAcknowledgmentNeverMutatesClinicalReview() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(sent()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(1);
        ExchangeTask delivered = new ExchangeTask(ID, 2, "LOCAL-1", WorkflowState.REQUESTED,
                TransmissionState.DELIVERED, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW);
        when(repository.find(ID)).thenReturn(Optional.of(delivered));

        ExchangeTask result = service.execute(ID, 1, ExchangeCommand.DELIVER, null);

        ArgumentCaptor<ExchangeTask> next = ArgumentCaptor.forClass(ExchangeTask.class);
        verify(repository).updateState(eq(ID), eq(1), next.capture());
        assertEquals(ClinicalReviewState.UNREVIEWED, next.getValue().clinicalReviewState());
        assertNull(next.getValue().reviewedBy());
        assertNull(next.getValue().reviewedAt());
        assertNull(next.getValue().reviewedRevision());
        assertEquals(ClinicalReviewState.UNREVIEWED, result.clinicalReviewState());
        verify(repository).appendEvent(ID, 2, ExchangeDimension.TRANSMISSION,
                "EXCHANGE_DELIVER", "sent", "delivered", null, null);
        verify(audit).append(any(), eq("EXCHANGE_DELIVER"), eq("EXCHANGE_DELIVER"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("SUCCESS"));
    }

    @Test
    void acknowledgeBindsClockAndRevisionAndNeverTrustsTheCallerForActor() {
        active("clinician");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(initial()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(1);
        ExchangeTask acknowledged = new ExchangeTask(ID, 2, "LOCAL-1", WorkflowState.REQUESTED,
                TransmissionState.PENDING, ClinicalReviewState.ACKNOWLEDGED,
                null, null, NOW, 1, NOW);
        when(repository.find(ID)).thenReturn(Optional.of(acknowledged));

        ExchangeTask result = service.execute(ID, 1, ExchangeCommand.ACKNOWLEDGE, null);

        ArgumentCaptor<ExchangeTask> next = ArgumentCaptor.forClass(ExchangeTask.class);
        verify(repository).updateState(eq(ID), eq(1), next.capture());
        assertEquals(NOW, next.getValue().reviewedAt());
        assertEquals(1, next.getValue().reviewedRevision());
        assertNull(next.getValue().reviewedBy());
        assertEquals(ClinicalReviewState.ACKNOWLEDGED, result.clinicalReviewState());
        verify(repository).appendEvent(ID, 2, ExchangeDimension.CLINICAL_REVIEW,
                "EXCHANGE_ACKNOWLEDGE", "unreviewed", "acknowledged", null, 1);
        verify(audit).append(any(), eq("EXCHANGE_ACKNOWLEDGE"), eq("EXCHANGE_ACKNOWLEDGE"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("SUCCESS"));
    }

    @Test
    void clinicalReviewIgnoresCallerReasonInBothStateAndLedger() {
        active("clinician");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(initial()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(1);
        ExchangeTask acknowledged = new ExchangeTask(ID, 2, "LOCAL-1", WorkflowState.REQUESTED,
                TransmissionState.PENDING, ClinicalReviewState.ACKNOWLEDGED,
                null, null, NOW, 1, NOW);
        when(repository.find(ID)).thenReturn(Optional.of(acknowledged));

        service.execute(ID, 1, ExchangeCommand.ACKNOWLEDGE, "caller supplied review note");

        ArgumentCaptor<ExchangeTask> next = ArgumentCaptor.forClass(ExchangeTask.class);
        verify(repository).updateState(eq(ID), eq(1), next.capture());
        assertNull(next.getValue().reason());
        // Only a workflow decision may record a reason. A review event must not
        // carry caller-supplied text that the task itself does not hold (XCH-02).
        verify(repository).appendEvent(ID, 2, ExchangeDimension.CLINICAL_REVIEW,
                "EXCHANGE_ACKNOWLEDGE", "unreviewed", "acknowledged", null, 1);
    }

    @Test
    void transmissionIgnoresCallerReasonInBothStateAndLedger() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(sent()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(1);
        ExchangeTask failed = new ExchangeTask(ID, 2, "LOCAL-1", WorkflowState.REQUESTED,
                TransmissionState.FAILED, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW);
        when(repository.find(ID)).thenReturn(Optional.of(failed));

        service.execute(ID, 1, ExchangeCommand.FAIL, "caller supplied delivery note");

        ArgumentCaptor<ExchangeTask> next = ArgumentCaptor.forClass(ExchangeTask.class);
        verify(repository).updateState(eq(ID), eq(1), next.capture());
        assertNull(next.getValue().reason());
        verify(repository).appendEvent(ID, 2, ExchangeDimension.TRANSMISSION,
                "EXCHANGE_FAIL", "sent", "failed", null, null);
    }

    @Test
    void acknowledgeDeniesNonClinicianAndInactiveMembership() {
        when(repository.hasActiveMembership("clinician-a")).thenReturn(true);
        when(repository.activeRoles("clinician-a")).thenReturn(List.of("lab_collector"));
        assertApi(403, () -> service.execute(ID, 1, ExchangeCommand.ACKNOWLEDGE, null));
        when(repository.activeRoles("clinician-a")).thenReturn(List.of("clinician"));
        when(repository.hasActiveMembership("clinician-a")).thenReturn(false);
        assertApi(403, () -> service.execute(ID, 1, ExchangeCommand.ACKNOWLEDGE, null));
        verify(repository, never()).updateState(any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(audit, org.mockito.Mockito.times(2)).append(any(), eq("EXCHANGE_REVIEW"),
                eq("EXCHANGE_REVIEW"), eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("DENIED"));
    }

    @Test
    void rejectAndCancelRequireAReasonAndOnlyTheyDo() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(initial()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(1);
        when(repository.find(ID)).thenReturn(Optional.of(new ExchangeTask(ID, 2, "LOCAL-1",
                WorkflowState.REJECTED, TransmissionState.PENDING, ClinicalReviewState.UNREVIEWED,
                "duplicate", null, null, null, NOW)));

        assertApi(422, () -> service.execute(ID, 1, ExchangeCommand.REJECT, null));
        assertApi(422, () -> service.execute(ID, 1, ExchangeCommand.CANCEL, "  "));
        assertApi(422, () -> service.execute(ID, 1, ExchangeCommand.REJECT, "x".repeat(501)));
        ExchangeTask rejected = service.execute(ID, 1, ExchangeCommand.REJECT, "duplicate order");
        assertEquals(WorkflowState.REJECTED, rejected.workflowState());
        verify(repository).appendEvent(ID, 2, ExchangeDimension.WORKFLOW,
                "EXCHANGE_REJECT", "requested", "rejected", "duplicate order", null);
    }

    @Test
    void staleVersionAndLostUpdateAreConflictsWithoutHistory() {
        active("clinician");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(new ExchangeTask(ID, 3, "LOCAL-1",
                WorkflowState.REQUESTED, TransmissionState.PENDING, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW)));
        assertApi(412, () -> service.execute(ID, 2, ExchangeCommand.ACCEPT, null));
        verify(repository, never()).appendEvent(any(), org.mockito.ArgumentMatchers.anyInt(), any(),
                any(), any(), any(), any(), any());

        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(initial()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(0);
        assertApi(412, () -> service.execute(ID, 1, ExchangeCommand.ACCEPT, null));
    }

    @Test
    void illegalTransitionAndMissingRecordAreRejected() {
        active("clinician");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(initial()));
        assertApi(409, () -> service.execute(ID, 1, ExchangeCommand.RESOLVE, null));
        verify(repository, never()).updateState(any(), org.mockito.ArgumentMatchers.anyInt(), any());

        when(repository.loadForUpdate(ID)).thenReturn(Optional.empty());
        assertApi(404, () -> service.execute(ID, 1, ExchangeCommand.ACCEPT, null));
        when(repository.find(ID)).thenReturn(Optional.empty());
        assertApi(404, () -> service.get(ID));
    }

    @Test
    void malformedIdIsUnprocessable() {
        assertApi(422, () -> service.execute("not-a-uuid", 1, ExchangeCommand.ACCEPT, null));
        assertApi(422, () -> service.get("not-a-uuid"));
        assertApi(422, () -> service.get(null));
    }

    @Test
    void readAuditsSuccessAndIsDeniedWithoutPermission() {
        active("reception");
        when(repository.find(ID)).thenReturn(Optional.of(initial()));
        ExchangeTaskEvent event = new ExchangeTaskEvent("event-1", ID, 1, ExchangeDimension.WORKFLOW,
                "EXCHANGE_CREATE", null, "requested", null, null, "clinician-a", NOW);
        when(repository.history(ID)).thenReturn(List.of(event));

        ExchangeTaskView view = service.get(ID);

        assertTrue(view.history().contains(event));
        assertEquals(1, view.task().version());
        verify(audit).append(any(), eq("EXCHANGE_READ"), eq("EXCHANGE_READ"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("SUCCESS"));

        when(repository.activeRoles("clinician-a")).thenReturn(List.of("privacy_auditor"));
        assertApi(403, () -> service.get(ID));
        verify(audit).append(any(), eq("EXCHANGE_READ"), eq("EXCHANGE_READ"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("DENIED"));
    }

    @Test
    void blankReasonIsTreatedAsAbsentForCommandsThatDoNotRequireIt() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(initial()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(1);
        when(repository.find(ID)).thenReturn(Optional.of(accepted()));

        service.execute(ID, 1, ExchangeCommand.ACCEPT, "  ");

        verify(repository).appendEvent(ID, 2, ExchangeDimension.WORKFLOW, "EXCHANGE_ACCEPT",
                "requested", "accepted", null, null);
    }

    private static void assertApi(int status, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
    }
}
