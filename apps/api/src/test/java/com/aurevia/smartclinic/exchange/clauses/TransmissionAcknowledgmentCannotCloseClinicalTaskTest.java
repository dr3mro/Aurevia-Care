package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.exchange.task.ClinicalReviewState;
import com.aurevia.smartclinic.exchange.task.ExchangeActionPolicy;
import com.aurevia.smartclinic.exchange.task.ExchangeCommand;
import com.aurevia.smartclinic.exchange.task.ExchangeDimension;
import com.aurevia.smartclinic.exchange.task.ExchangeTask;
import com.aurevia.smartclinic.exchange.task.ExchangeTaskRepository;
import com.aurevia.smartclinic.exchange.task.ExchangeTaskService;
import com.aurevia.smartclinic.exchange.task.TransmissionState;
import com.aurevia.smartclinic.exchange.task.WorkflowState;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * XCH-02 clause 3 (API test): a transmission delivery receipt cannot close or
 * advance a clinical task. Every case enters through
 * {@link com.aurevia.smartclinic.exchange.task.ExchangeTaskService#execute}, the
 * command invoked by the exchange-task controller. The real action policy and
 * state machine run inside a transaction callback; only the repository, audit
 * and transaction manager are mocked, so a denial is proven to leave the task
 * and its append-only event ledger untouched. All identifiers are synthetic.
 */
class TransmissionAcknowledgmentCannotCloseClinicalTaskTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "clinician-a";
    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ORDER = "LOCAL-XCH-02-3";
    private static final Instant NOW = Instant.parse("2026-11-23T09:00:00Z");

    private ExchangeTaskRepository repository;
    private AuditAppender audit;
    private ExchangeTaskService service;

    /** Build the real command service over synthetic collaborators and tenant context. */
    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, "EXCHANGE", "corr-xch02-clause3"));
        repository = mock(ExchangeTaskRepository.class);
        audit = mock(AuditAppender.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new ExchangeTaskService(repository, new ExchangeActionPolicy(),
                new TransactionTemplate(manager), audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Remove the synthetic tenant context so other tests are unaffected. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Synthetic task whose payload was sent and whose clinical review is still open. */
    private static ExchangeTask sentTask() {
        return new ExchangeTask(ID, 1, ORDER, WorkflowState.REQUESTED,
                TransmissionState.SENT, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW.minusSeconds(60));
    }

    /** Synthetic task that has not been handed to a partner channel yet. */
    private static ExchangeTask pendingTask() {
        return new ExchangeTask(ID, 1, ORDER, WorkflowState.REQUESTED,
                TransmissionState.PENDING, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW.minusSeconds(60));
    }

    /** The expected snapshot after a delivery receipt: transmission only, review untouched. */
    private static ExchangeTask deliveredTask() {
        return new ExchangeTask(ID, 2, ORDER, WorkflowState.REQUESTED,
                TransmissionState.DELIVERED, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW);
    }

    /** Grant the synthetic subject an active membership and exactly the supplied roles. */
    private void active(String... roles) {
        when(repository.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(repository.activeRoles(SUBJECT)).thenReturn(List.of(roles));
    }

    /** Assert the status and machine-readable code of a refused command. */
    private static ApiException assertDenial(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
        return failure;
    }

    /** Assert that a refusal wrote neither the task state nor the event ledger. */
    private void assertNoWrite() {
        verify(repository, never()).updateState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    /** A delivery receipt moves only the transmission channel and binds no review evidence. */
    @Test
    void deliveryDoesNotChangeClinicalReviewOrBindHumanEvidence() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(sentTask()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(1);
        when(repository.find(ID)).thenReturn(Optional.of(deliveredTask()));

        ExchangeTask result = service.execute(ID, 1, ExchangeCommand.DELIVER, null);

        ArgumentCaptor<ExchangeTask> written = ArgumentCaptor.forClass(ExchangeTask.class);
        verify(repository).updateState(eq(ID), eq(1), written.capture());
        assertEquals(2, written.getValue().version());
        assertEquals(TransmissionState.DELIVERED, written.getValue().transmissionState());
        assertEquals(WorkflowState.REQUESTED, written.getValue().workflowState());
        assertEquals(ClinicalReviewState.UNREVIEWED, written.getValue().clinicalReviewState());
        assertNull(written.getValue().reason());
        assertNull(written.getValue().reviewedBy());
        assertNull(written.getValue().reviewedAt());
        assertNull(written.getValue().reviewedRevision());
        assertEquals(2, result.version());
        assertEquals(ClinicalReviewState.UNREVIEWED, result.clinicalReviewState());
        assertEquals(TransmissionState.DELIVERED, result.transmissionState());
        verify(repository).appendEvent(ID, 2, ExchangeDimension.TRANSMISSION,
                "EXCHANGE_DELIVER", "sent", "delivered", null, null);
        verify(repository, never()).appendEvent(eq(ID), anyInt(), eq(ExchangeDimension.CLINICAL_REVIEW),
                any(), any(), any(), any(), any());
        verify(audit).append(any(), eq("EXCHANGE_DELIVER"), eq("EXCHANGE_DELIVER"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("SUCCESS"));
    }

    /** A lab collector may record delivery but cannot acknowledge or resolve the clinical task. */
    @Test
    void deliveryCannotCloseATaskWhenActorIsOnlyALabCollector() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(sentTask()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(1);
        when(repository.find(ID)).thenReturn(Optional.of(deliveredTask()));

        ExchangeTask delivered = service.execute(ID, 1, ExchangeCommand.DELIVER, null);

        assertEquals(TransmissionState.DELIVERED, delivered.transmissionState());
        verify(repository).updateState(eq(ID), eq(1), any());
        verify(repository).appendEvent(ID, 2, ExchangeDimension.TRANSMISSION,
                "EXCHANGE_DELIVER", "sent", "delivered", null, null);
        verify(audit).append(any(), eq("EXCHANGE_DELIVER"), eq("EXCHANGE_DELIVER"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("SUCCESS"));

        // Scope the denial assertions to the two review commands: the DELIVER
        // control above legitimately wrote one event and one success audit.
        clearInvocations(repository, audit);

        assertDenial(403, "FORBIDDEN", () -> service.execute(ID, 1, ExchangeCommand.RESOLVE, null));
        assertDenial(403, "FORBIDDEN", () -> service.execute(ID, 1, ExchangeCommand.ACKNOWLEDGE, null));
        assertNoWrite();
        // Both denials audit the dimension's policy action, never the command action.
        verify(audit, times(2)).append(any(), eq("EXCHANGE_REVIEW"), eq("EXCHANGE_REVIEW"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("DENIED"));
    }

    /** A version precondition protects the delivery receipt against a stale read. */
    @Test
    void deliveryIsRefusedOnStaleVersion() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(new ExchangeTask(ID, 2, ORDER,
                WorkflowState.REQUESTED, TransmissionState.SENT, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW)));

        assertDenial(412, "STALE_VERSION", () -> service.execute(ID, 1, ExchangeCommand.DELIVER, null));

        assertNoWrite();
    }

    /** A delivery receipt is illegal before the payload has been sent. */
    @Test
    void deliveryIsRefusedFromPending() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(pendingTask()));

        assertDenial(409, "INVALID_TRANSITION", () -> service.execute(ID, 1, ExchangeCommand.DELIVER, null));

        assertNoWrite();
    }

    /** Closing a clinical task requires the full human review chain, not a delivery receipt. */
    @Test
    void resolveFromUnreviewedIsRefused() {
        active("clinician");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(pendingTask()));

        assertDenial(409, "INVALID_TRANSITION", () -> service.execute(ID, 1, ExchangeCommand.RESOLVE, null));

        assertNoWrite();
    }

    /**
     * A task outside the active tenant is concealed with 404. With a mocked
     * repository the empty {@code loadForUpdate} result models the row that forced
     * row-level security hides; the SQL filter itself is proven end to end by
     * {@code it.exchangetask.ExchangeTaskIsolationTest} and the V20 migration. This
     * case proves the service's not-found denial and the absence of writes. Only
     * the organization changes; the subject stays the one the role stub grants.
     */
    @Test
    void deliveryOfAnotherTenantsTaskIsConcealed() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.empty());
        try {
            TenantContext.set(new TenantContext(OTHER_ORG, SUBJECT, "EXCHANGE", "corr-other-org"));

            assertDenial(404, "NOT_FOUND", () -> service.execute(ID, 1, ExchangeCommand.DELIVER, null));

            assertNoWrite();
        } finally {
            TenantContext.set(new TenantContext(ORG, SUBJECT, "EXCHANGE", "corr-xch02-clause3"));
        }
    }

    /** A lost compare-and-set is refused deterministically without an event or success audit. */
    @Test
    void deliveryIsRefusedWhenTheCompareAndSetLosesTheRace() {
        active("lab_collector");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(sentTask()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(0);

        assertDenial(412, "STALE_VERSION", () -> service.execute(ID, 1, ExchangeCommand.DELIVER, null));

        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), eq("SUCCESS"));
    }

    /** A revoked subject cannot record even the delivery receipt on the next action. */
    @Test
    void deliveryIsRefusedWhenTheActorHasNoActiveMembership() {
        when(repository.hasActiveMembership(SUBJECT)).thenReturn(false);
        when(repository.activeRoles(SUBJECT)).thenReturn(List.of("lab_collector"));

        assertDenial(403, "FORBIDDEN", () -> service.execute(ID, 1, ExchangeCommand.DELIVER, null));

        assertNoWrite();
        verify(audit).append(any(), eq("EXCHANGE_TRANSMISSION"), eq("EXCHANGE_TRANSMISSION"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("DENIED"));
    }
}
