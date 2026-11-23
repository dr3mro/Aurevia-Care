package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * XCH-02 clause 2 (API test): acknowledgment is made by an authorized human with
 * a timestamp and a revision. Every case enters through
 * {@link com.aurevia.smartclinic.exchange.task.ExchangeTaskService#execute}, the
 * same command the exchange controller calls, and uses synthetic subjects only.
 *
 * <p>This is a database-free service proof: it verifies that only a clinician
 * with active membership may acknowledge, that the injected clock instant and
 * the exact reviewed version are bound to the command, and that the caller
 * cannot supply an actor. The actor identity itself is derived by the V20
 * trigger at write time and is proven by the existing HTTP-plus-PostgreSQL
 * integration test, not by this mock. The snapshot therefore carries a null
 * {@code reviewedBy} here by design.</p>
 */
class AcknowledgmentMadeAuthorizedHumanTimestampRevisionTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SUBJECT = "clinician-a";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final String ORDER_REF = "LOCAL-1";

    private ExchangeTaskRepository repository;
    private AuditAppender audit;
    private ExchangeTaskService service;

    /** Build the service with a fixed clock and a synthetic tenant context. */
    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, "EXCHANGE", "corr-xch02-clause-2"));
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

    /** Grant the caller an active membership and the given roles. */
    private void active(String... roles) {
        when(repository.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(repository.activeRoles(SUBJECT)).thenReturn(List.of(roles));
    }

    /** Synthetic unreviewed task at version one. */
    private static ExchangeTask unreviewed() {
        return new ExchangeTask(ID, 1, ORDER_REF, WorkflowState.REQUESTED,
                TransmissionState.PENDING, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW.minusSeconds(60));
    }

    /** Synthetic already-acknowledged task at the given version. */
    private static ExchangeTask acknowledged(int version) {
        return new ExchangeTask(ID, version, ORDER_REF, WorkflowState.REQUESTED,
                TransmissionState.PENDING, ClinicalReviewState.ACKNOWLEDGED,
                null, null, NOW, 1, NOW.minusSeconds(60));
    }

    /** Capture and return the failure raised by a refused command. */
    private static ApiException reject(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
        return failure;
    }

    /** Assert that a refusal wrote neither the task state nor the ledger. */
    private void assertNoStateWrite() {
        verify(repository, never()).updateState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    /** Assert that a refusal reached the audit boundary at all. */
    private void assertNoAudit() {
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    /** A current clinician may acknowledge; the clock and reviewed version are bound. */
    @Test
    void acknowledgeByAuthorizedClinicianBindsClockAndRevision() {
        active("clinician");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(unreviewed()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(1);
        when(repository.find(ID)).thenReturn(Optional.of(acknowledged(2)));

        service.execute(ID, 1, ExchangeCommand.ACKNOWLEDGE, null);

        // Assert on the snapshot handed to the guarded write, not on the stubbed
        // find() result: the latter would pass even if no transition happened.
        ArgumentCaptor<ExchangeTask> next = ArgumentCaptor.forClass(ExchangeTask.class);
        verify(repository).updateState(eq(ID), eq(1), next.capture());
        assertEquals(NOW, next.getValue().reviewedAt());
        assertEquals(1, next.getValue().reviewedRevision());
        assertEquals(2, next.getValue().version());
        assertEquals(ClinicalReviewState.ACKNOWLEDGED, next.getValue().clinicalReviewState());
        // Null is the safe value here: the service never accepts or invents an
        // actor; V20 derives the real actor from the transaction context.
        assertNull(next.getValue().reviewedBy());
        verify(repository).appendEvent(ID, 2, ExchangeDimension.CLINICAL_REVIEW,
                "EXCHANGE_ACKNOWLEDGE", "unreviewed", "acknowledged", null, 1);
        verify(audit).append(any(), eq("EXCHANGE_ACKNOWLEDGE"), eq("EXCHANGE_ACKNOWLEDGE"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("SUCCESS"));
    }

    /** An active collector may record transport but may not acknowledge clinically. */
    @Test
    void acknowledgeIsRefusedForNonClinicianRole() {
        active("lab_collector");

        ApiException failure = reject(403, "FORBIDDEN",
                () -> service.execute(ID, 1, ExchangeCommand.ACKNOWLEDGE, null));

        assertEquals("missing exchange permission", failure.getMessage());
        verify(audit).append(any(), eq("EXCHANGE_REVIEW"), eq("EXCHANGE_REVIEW"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("DENIED"));
        verify(repository, never()).loadForUpdate(any());
        assertNoStateWrite();
    }

    /** A revoked human is denied on the next action even with the clinician role. */
    @Test
    void acknowledgeIsRefusedWhenMembershipIsInactive() {
        when(repository.hasActiveMembership(SUBJECT)).thenReturn(false);

        ApiException failure = reject(403, "FORBIDDEN",
                () -> service.execute(ID, 1, ExchangeCommand.ACKNOWLEDGE, null));

        assertEquals("no active membership", failure.getMessage());
        verify(audit).append(any(), eq("EXCHANGE_REVIEW"), eq("EXCHANGE_REVIEW"),
                eq("EXCHANGE_TASK"), eq("EXCHANGE"), eq(ID), eq("DENIED"));
        verify(repository, never()).activeRoles(any());
        assertNoStateWrite();
    }

    /** A version the caller did not read cannot be acknowledged. */
    @Test
    void acknowledgeIsRefusedOnStaleVersion() {
        active("clinician");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(unreviewed()));

        reject(412, "STALE_VERSION", () -> service.execute(ID, 2, ExchangeCommand.ACKNOWLEDGE, null));

        assertNoStateWrite();
        assertNoAudit();
    }

    /** A concurrent write that wins the compare-and-set leaves no review evidence. */
    @Test
    void acknowledgeIsRefusedWhenTheRowChangedBetweenReadAndWrite() {
        active("clinician");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(unreviewed()));
        when(repository.updateState(eq(ID), eq(1), any())).thenReturn(0);

        reject(412, "STALE_VERSION", () -> service.execute(ID, 1, ExchangeCommand.ACKNOWLEDGE, null));

        verify(repository).updateState(eq(ID), eq(1), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    /** An acknowledged item cannot be acknowledged again at the same revision. */
    @Test
    void acknowledgeIsRefusedFromAlreadyReviewedState() {
        active("clinician");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(acknowledged(2)));

        reject(409, "INVALID_TRANSITION",
                () -> service.execute(ID, 2, ExchangeCommand.ACKNOWLEDGE, null));

        assertNoStateWrite();
        assertNoAudit();
    }
}
