package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.messaging.conversations.ConversationActions;
import com.aurevia.messaging.conversations.ConversationContract.CloseRequest;
import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.ReplyRequest;
import com.aurevia.messaging.conversations.ConversationContract.ResolveRequest;
import com.aurevia.messaging.conversations.ConversationContract.State;
import com.aurevia.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.messaging.conversations.ConversationContract.View;
import com.aurevia.messaging.conversations.ConversationRepository;
import com.aurevia.messaging.conversations.ConversationService;
import com.aurevia.messaging.units.CareUnitDirectoryService;
import com.aurevia.messaging.units.InMemoryMessagingStore;
import com.aurevia.messaging.units.MessagingActions;
import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.tenant.TenantContext;

/**
 * Clause-focused proof for MSG-02 state tracking: a conversation work item moves
 * through {@code awaiting_staff}, {@code awaiting_patient}, {@code resolved} and
 * {@code closed}, a reply reopens a resolved item only while the relationship
 * still permits messaging, and the service refuses a closed thread, a stale
 * version, a wrong-unit actor, another patient's thread and a missing reason.
 *
 * <p>The entry point is {@link ConversationService}, not the state enum: every
 * state change is asserted through the persisted wire key passed to
 * {@link ConversationRepository#updateState} and the work-item fact passed to
 * {@link ConversationRepository#appendEvent}. The repository is mocked so the
 * proof stays database-free; real SQL, RLS and the HTTP entry point belong to
 * {@code ConversationFlowTest}. All identifiers and text are synthetic.</p>
 */
@ExtendWith(MockitoExtension.class)
class TrackAwaitingStaffAwaitingPatientResolvedClosedTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String CONNECTION_ID = "44444444-4444-4444-4444-444444444444";
    private static final String LAB_UNIT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @Mock
    private ConversationRepository repository;
    @Mock
    private TransactionTemplate transactions;
    @Mock
    private AuditAppender audit;

    private InMemoryMessagingStore unitStore;
    private ConversationService service;

    /**
     * Build the service with the real action and connection matrices so the
     * guard decisions are real, and a transaction template that runs its
     * callback synchronously so a denial is observable without a database.
     */
    @BeforeEach
    void setUp() {
        unitStore = new InMemoryMessagingStore();
        CareUnitDirectoryService directory = new CareUnitDirectoryService(unitStore, new MessagingActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ConversationService(repository, new ConversationActions(), transactions, audit, directory);
        lenient().when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        bind("patient-a");
        unitStore.addMember(ORG, "patient-a", "patient", true);
        unitStore.addPatient(ORG, PATIENT_ID);
        unitStore.linkPatient(ORG, "patient-a", PATIENT_ID);
        unitStore.addUnit(new CareUnit(LAB_UNIT_ID, ORG, "facility-1", "Synthetic Lab",
                List.of("results"), true, "lab"));
        seedConnection(CONNECTION_ID, PATIENT_ID, ConnectionState.ACTIVE);
    }

    /** Clear the thread-local tenant context so tests cannot leak a caller. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "PURPOSE", "corr-1"));
    }

    private void asPatient(String subject) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("patient"));
    }

    private void asStaff(String subject, String role) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(role));
        unitStore.addMember(ORG, subject, role, true);
    }

    private static Conversation conversation(State state, int version) {
        return conversation(state, version, "patient-a", "lab");
    }

    private static Conversation conversation(State state, int version, String patientSubject, String unitKey) {
        return new Conversation(ID, version, PATIENT_ID, patientSubject, unitKey, CONNECTION_ID,
                "topic", state, null, 0, 0, NOW);
    }

    private void seedConnection(String id, String patientId, ConnectionState state) {
        unitStore.insertConnection(ORG, new PatientUnitConnection(id, ORG, patientId,
                LAB_UNIT_ID, ConnectionContext.ORDER, "order-ref-" + id, "rel-" + id,
                state, "patient-a", NOW, NOW, null));
    }

    @Test
    void workItemMovesFromAwaitingStaffToAwaitingPatientThenResolvedAndClosed() {
        asStaff("lab-a", "lab_verifier");
        // A chained reply, resolve and close reads and writes three revisions.
        // Consecutive returns keep each command's read model in step with the
        // version the next command will present as its precondition.
        when(repository.loadForUpdate(ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, 1)),
                Optional.of(conversation(State.AWAITING_PATIENT, 2)),
                Optional.of(conversation(State.RESOLVED, 3)));
        when(repository.find(ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_PATIENT, 2)),
                Optional.of(conversation(State.RESOLVED, 3)),
                Optional.of(conversation(State.CLOSED, 4)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.nextSequence(ID)).thenReturn(2);
        when(repository.messages(ID)).thenReturn(List.of());
        when(repository.events(ID)).thenReturn(List.of());

        View replied = service.reply(ID, new ReplyRequest("please arrive early"), 1);
        assertEquals(State.AWAITING_PATIENT, replied.conversation().state());
        verify(repository).updateState(ID, 1, "awaiting_patient");

        Conversation resolved = service.resolve(ID, new ResolveRequest(" answered "), 2);
        assertEquals(State.RESOLVED, resolved.state());
        verify(repository).updateState(ID, 2, "resolved");
        verify(repository).appendEvent(ID, 3, "resolved", "lab-a", "awaiting_patient", "resolved", "answered");

        Conversation closed = service.close(ID, new CloseRequest(" done "), 3);
        assertEquals(State.CLOSED, closed.state());
        verify(repository).updateState(ID, 3, "closed");
        verify(repository).appendEvent(ID, 4, "closed", "lab-a", "resolved", "closed", "done");
        verify(repository, never()).appendEvent(eq(ID), anyInt(), eq("reopened"), any(), any(), any(), any());
    }

    @Test
    void aReplyReopensAResolvedWorkItemToAwaitingStaff() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.RESOLVED, 2)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.nextSequence(ID)).thenReturn(4);
        when(repository.find(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 3)));
        when(repository.messages(ID)).thenReturn(List.of());
        when(repository.events(ID)).thenReturn(List.of());

        View reopened = service.reply(ID, new ReplyRequest("one more question"), 2);

        assertEquals(State.AWAITING_STAFF, reopened.conversation().state());
        verify(repository).updateState(ID, 2, "awaiting_staff");
        verify(repository).appendEvent(ID, 3, "reopened", "patient-a", "resolved", "awaiting_staff",
                "reply reopened the work item");
    }

    @Test
    void aReplyToAResolvedWorkItemWithARevokedLinkDoesNotReopen() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.RESOLVED, 2)));
        // The clause reopens only while the relationship still permits
        // messaging: the inactive link gate precedes the reopen event.
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 2));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).insertMessage(any(), anyInt(), any(), any(), any(), any());
        verify(repository, never()).updateState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void replyToAClosedThreadIsRefusedAndStateIsUnchanged() {
        asPatient("patient-a");
        Conversation closed = conversation(State.CLOSED, 2);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(closed));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 2));

        assertEquals(409, failure.status());
        assertEquals("CLOSED_THREAD", failure.code());
        verify(repository, never()).insertMessage(any(), anyInt(), any(), any(), any(), any());
        verify(repository, never()).updateState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());

        // A read by an authorized viewer still works; only writes are barred.
        when(repository.find(ID)).thenReturn(Optional.of(closed));
        when(repository.messages(ID)).thenReturn(List.of());
        when(repository.events(ID)).thenReturn(List.of());
        assertEquals(State.CLOSED, service.get(ID).conversation().state());
    }

    @Test
    void aStaleVersionCannotMoveTheWorkItem() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 2)));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.resolve(ID, new ResolveRequest("done"), 1));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        verify(repository, never()).updateState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void wrongUnitStaffCannotTransitionTheWorkItem() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(
                conversation(State.AWAITING_STAFF, 1, "patient-a", "clinic")));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.resolve(ID, new ResolveRequest("done"), 1));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).updateState(any(), anyInt(), any());
    }

    @Test
    void resolvingWithoutAReasonIsRefused() {
        // Reason validation throws before execute(), so authorize is never
        // reached and no repository stub is needed.
        ApiException failure = assertThrows(ApiException.class,
                () -> service.resolve(ID, new ResolveRequest("  "), 1));

        assertEquals(422, failure.status());
        assertEquals("INVALID_REASON", failure.code());
        verify(repository, never()).loadForUpdate(any());
    }

    @Test
    void aPatientCannotTransitionAnotherPatientsWorkItem() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(
                conversation(State.AWAITING_STAFF, 1, "patient-b", "lab")));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).updateState(any(), anyInt(), any());
    }
}
