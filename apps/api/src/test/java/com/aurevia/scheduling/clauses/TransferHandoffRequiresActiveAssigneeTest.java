package com.aurevia.scheduling.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.scheduling.AppointmentService;
import com.aurevia.scheduling.SchedulingRepository;
import com.aurevia.scheduling.checkin.CheckInCommands.Handoff;
import com.aurevia.scheduling.checkin.CheckInService;
import com.aurevia.scheduling.checkin.HandoffEvent;
import com.aurevia.scheduling.checkin.WorklistItem;
import com.aurevia.scheduling.checkin.WorklistPolicy;
import com.aurevia.scheduling.checkin.WorklistRepository;
import com.aurevia.tenant.TenantContext;

/**
 * CLN-02 clause 3 (API test): a transfer handoff is permitted only when the
 * worklist item currently has an assignee whose membership is still active and
 * the receiving member is active too. Every case enters through
 * {@link com.aurevia.scheduling.checkin.CheckInService#handoff}, the
 * same command the clinic controller calls, and uses synthetic subjects only.
 * The mocked transaction runs the real guarded callback so that a refusal is
 * proven to leave both the item and the append-only handoff ledger untouched.
 */
class TransferHandoffRequiresActiveAssigneeTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "reception-a";
    private static final String APPOINTMENT = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ITEM = "44444444-4444-4444-4444-444444444444";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final String SOURCE = "nurse-a";
    private static final String TARGET = "nurse-b";

    private WorklistRepository repository;
    private SchedulingRepository scheduling;
    private AuditAppender audit;
    private CheckInService service;

    /** Build the service with the tenant context and fixed clock the clause needs. */
    @BeforeEach
    void setUp() {
        repository = mock(WorklistRepository.class);
        scheduling = mock(SchedulingRepository.class);
        audit = mock(AuditAppender.class);
        service = new CheckInService(repository, mock(AppointmentService.class), scheduling,
                new WorklistPolicy(), transactions(), audit, Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, SUBJECT, "CHECK_IN_WORKLIST", "corr-clause-3"));
    }

    /** Remove the synthetic tenant context so other tests are unaffected. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Transaction template that runs the guarded callback synchronously, as production does. */
    private TransactionTemplate transactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }

    /** Grant the caller an active membership and a role allowed to hand off. */
    private void authorizedReception() {
        when(scheduling.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(scheduling.activeRoles(SUBJECT)).thenReturn(List.of("reception"));
    }

    /** Synthetic worklist entry at version one, optionally already assigned. */
    private WorklistItem item(String assignee, Instant assignedAt, int version) {
        return new WorklistItem(ITEM, APPOINTMENT, null, PATIENT, "MAIN", NOW, 0,
                assignee, assignedAt, version);
    }

    /** Capture the ApiException raised by a refused command. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert that a refusal wrote neither the item nor the handoff ledger. */
    private void assertNoHandoffWasWritten() {
        verify(repository, never()).updateItem(anyString(), anyInt(), anyString(), any(), anyInt());
        verify(repository, never()).insertHandoff(any());
    }

    /** A current active assignee may transfer the item to another active member. */
    @Test
    void handoffTransfersAnAssignedItemToAnotherActiveMemberAndRecordsTheEvent() {
        authorizedReception();
        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(SOURCE, NOW, 1)));
        when(scheduling.hasActiveMembership(SOURCE)).thenReturn(true);
        when(scheduling.hasActiveMembership(TARGET)).thenReturn(true);
        when(repository.updateItem(ITEM, 1, TARGET, NOW, 0)).thenReturn(1);

        WorklistItem result = service.handoff(ITEM, new Handoff(TARGET, "coverage"), 1);

        assertEquals(TARGET, result.assigneeSubject());
        assertEquals(NOW, result.assignedAt());
        assertEquals(2, result.version());
        verify(repository).updateItem(ITEM, 1, TARGET, NOW, 0);
        org.mockito.ArgumentCaptor<HandoffEvent> captor =
                org.mockito.ArgumentCaptor.forClass(HandoffEvent.class);
        verify(repository).insertHandoff(captor.capture());
        assertEquals(ITEM, captor.getValue().itemId());
        assertEquals(SOURCE, captor.getValue().fromSubject());
        assertEquals(TARGET, captor.getValue().toSubject());
        assertEquals("coverage", captor.getValue().reason());
        assertEquals(SUBJECT, captor.getValue().actor());
        assertEquals(NOW, captor.getValue().occurredAt());
    }

    /** An item with no assignee is refused before any write. */
    @Test
    void handoffIsRefusedWhenTheItemHasNoAssignee() {
        authorizedReception();
        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(null, null, 1)));

        ApiException failure = reject(() -> service.handoff(ITEM, new Handoff(TARGET, "coverage"), 1));

        assertEquals(409, failure.status());
        assertEquals("NO_ACTIVE_ASSIGNEE", failure.code());
        assertNoHandoffWasWritten();
    }

    /** A former assignee whose membership was deactivated cannot hand off. */
    @Test
    void handoffIsRefusedWhenTheCurrentAssigneeIsNoLongerActive() {
        authorizedReception();
        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(SOURCE, NOW, 1)));
        when(scheduling.hasActiveMembership(SOURCE)).thenReturn(false);

        ApiException failure = reject(() -> service.handoff(ITEM, new Handoff(TARGET, "coverage"), 1));

        assertEquals(409, failure.status());
        assertEquals("ASSIGNEE_INACTIVE", failure.code());
        assertNoHandoffWasWritten();
    }

    /** The receiving member must also hold active membership in the tenant. */
    @Test
    void handoffIsRefusedWhenTheReceivingMemberIsNotActive() {
        authorizedReception();
        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(SOURCE, NOW, 1)));
        when(scheduling.hasActiveMembership(SOURCE)).thenReturn(true);
        when(scheduling.hasActiveMembership(TARGET)).thenReturn(false);

        ApiException failure = reject(() -> service.handoff(ITEM, new Handoff(TARGET, "coverage"), 1));

        assertEquals(422, failure.status());
        assertEquals("INVALID_ASSIGNEE", failure.code());
        assertNoHandoffWasWritten();
    }
}
