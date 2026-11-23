package com.aurevia.smartclinic.messaging.clauses;

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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.messaging.conversations.ConversationActions;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.AssignRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.CloseRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.Message;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.ReplyRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.ResolveRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.SenderKind;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.State;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.TransferRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.View;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.WorkEventType;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.WorkItemEvent;
import com.aurevia.smartclinic.messaging.conversations.ConversationRepository;
import com.aurevia.smartclinic.messaging.conversations.ConversationService;
import com.aurevia.smartclinic.messaging.conversations.PatientConnectionVerifier;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * MSG-02 clause 7 (API test): a closed conversation stays readable to an
 * authorized viewer while every message and work-item write is refused.
 * Every case enters through {@link com.aurevia.smartclinic.messaging.conversations.ConversationService},
 * the same domain boundary the controller calls, with a mocked repository and a
 * transaction template that runs the real guarded callback. The synthetic
 * tenant context is bound for each case and always cleared afterwards; no real
 * patient data, credential or persistent store is involved.
 */
class ClosedThreadsReadAuthorizedViewersTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String PATIENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String CONNECTION_ID = "44444444-4444-4444-4444-444444444444";
    private static final String LAB_STAFF = "lab-staff-a";
    private static final String OTHER_LAB_STAFF = "lab-staff-b";
    private static final String RECEPTION = "reception-a";
    private static final String PATIENT_A = "patient-a";
    private static final String OTHER_PATIENT = "patient-b";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private ConversationRepository repository;
    private PatientConnectionVerifier connections;
    private AuditAppender audit;
    private ConversationService service;

    /** Build the real service over mocked boundaries and bind a synthetic caller. */
    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
        connections = mock(PatientConnectionVerifier.class);
        audit = mock(AuditAppender.class);
        service = new ConversationService(repository, new ConversationActions(), transactions(), audit, connections);
        bind(LAB_STAFF);
    }

    /** Remove the synthetic tenant context so a later test cannot inherit it. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Bind a fresh synthetic tenant context for the caller under test. */
    private static void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "PURPOSE", "corr-closed-thread"));
    }

    /** Grant the caller an active membership and a role allowed to work the lab unit. */
    private void asLabStaff(String subject) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("lab_verifier"));
    }

    /** Grant the caller reception: a staff role that is not eligible for the lab unit. */
    private void asReception(String subject) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("reception"));
    }

    /** Grant the caller the patient role bound to a chosen synthetic subject. */
    private void asPatient(String subject) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("patient"));
    }

    /**
     * Transaction template that executes the guarded callback synchronously so a
     * refusal is observed at the service boundary exactly as production raises it.
     */
    private static TransactionTemplate transactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }

    /** Synthetic closed lab conversation at version two. */
    private static Conversation closedConversation() {
        return new Conversation(ID, 2, PATIENT_ID, PATIENT_A, UnitKey.LAB.wire(), CONNECTION_ID,
                "topic", State.CLOSED, LAB_STAFF, 0, 0, NOW);
    }

    /** Synthetic message proving the closed trail is still readable. */
    private static Message message() {
        return new Message(ID, ID, 1, SenderKind.PATIENT, PATIENT_A, "patient", "synthetic body", NOW);
    }

    /** Synthetic close event proving the work-item trail is still readable. */
    private static WorkItemEvent event() {
        return new WorkItemEvent(ID, ID, 2, WorkEventType.CLOSED, LAB_STAFF,
                State.RESOLVED.wire(), State.CLOSED.wire(), "done", NOW);
    }

    /** Capture the structured failure raised by a refused command. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert that a work-item command is refused specifically because the thread is closed. */
    private static void assertClosedThread(Runnable command) {
        ApiException failure = reject(command);
        assertEquals(409, failure.status());
        assertEquals("CLOSED_THREAD", failure.code());
    }

    /** Assert that neither conversation state nor either append-only trail was written. */
    private void assertNoWrites() {
        verify(repository, never()).insertMessage(anyString(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(repository, never()).updateState(anyString(), anyInt(), anyString());
        verify(repository, never()).assign(anyString(), anyInt(), anyString());
        verify(repository, never()).appendEvent(anyString(), anyInt(), anyString(), anyString(),
                any(), any(), any());
    }

    /** An eligible lab viewer can still read a closed thread and both of its trails. */
    @Test
    void closedThreadRemainsReadableToAuthorizedViewer() {
        asLabStaff(LAB_STAFF);
        when(repository.find(ID)).thenReturn(Optional.of(closedConversation()));
        when(repository.messages(ID)).thenReturn(List.of(message()));
        when(repository.events(ID)).thenReturn(List.of(event()));

        View view = service.get(ID);

        assertEquals(ID, view.conversation().id());
        assertEquals(State.CLOSED, view.conversation().state());
        assertEquals(1, view.messages().size());
        assertEquals(1, view.events().size());
        assertEquals("synthetic body", view.messages().get(0).body());
        assertEquals(WorkEventType.CLOSED, view.events().get(0).eventType());
        assertNoWrites();
    }

    /** The verified patient who owns a closed thread is also an authorized viewer. */
    @Test
    void closedThreadRemainsReadableToAuthorizedPatient() {
        asPatient(PATIENT_A);
        when(repository.find(ID)).thenReturn(Optional.of(closedConversation()));
        when(repository.messages(ID)).thenReturn(List.of(message()));
        when(repository.events(ID)).thenReturn(List.of(event()));

        View view = service.get(ID);

        assertEquals(State.CLOSED, view.conversation().state());
        assertEquals(1, view.messages().size());
        assertEquals(1, view.events().size());
        assertNoWrites();
    }

    /** A reply to a closed thread is refused with 409 CLOSED_THREAD before any write. */
    @Test
    void replyToClosedThreadIsRefusedWithCLOSED_THREAD() {
        asLabStaff(LAB_STAFF);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(closedConversation()));

        ApiException failure = reject(() -> service.reply(ID, new ReplyRequest("synthetic body"), 2));

        assertEquals(409, failure.status());
        assertEquals("CLOSED_THREAD", failure.code());
        assertNoWrites();
        // Staff callers never resolve a connection; the 409 is the closed-state rule.
        verify(connections, never()).requireActive(anyString(), anyString(), anyString());
    }

    /** A patient's reply is refused by the closed-state guard before connection re-resolution. */
    @Test
    void patientReplyToClosedThreadIsRefusedBeforeConnectionVerification() {
        asPatient(PATIENT_A);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(closedConversation()));
        when(repository.patientLinkActive(PATIENT_A, PATIENT_ID)).thenReturn(true);

        ApiException failure = reject(() -> service.reply(ID, new ReplyRequest("synthetic body"), 2));

        assertEquals(409, failure.status());
        assertEquals("CLOSED_THREAD", failure.code());
        // With an active link, a missing closed guard would reach connection resolution.
        verify(connections, never()).requireActive(anyString(), anyString(), anyString());
        assertNoWrites();
    }

    /** Assign, transfer, resolve and close all hit the same closed-thread guard. */
    @Test
    void assignTransferResolveAndCloseOnClosedThreadAreRefused() {
        asLabStaff(LAB_STAFF);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(closedConversation()));

        assertClosedThread(() -> service.assign(ID, new AssignRequest(OTHER_LAB_STAFF, "cover"), 2));
        assertClosedThread(() -> service.transfer(ID, new TransferRequest(OTHER_LAB_STAFF, "handover"), 2));
        assertClosedThread(() -> service.resolve(ID, new ResolveRequest("answered"), 2));
        assertClosedThread(() -> service.close(ID, new CloseRequest("done"), 2));

        assertNoWrites();
    }

    /**
     * A viewer outside the unit or bound to another patient is refused before the
     * closed-state check: even with a matching version a mutating command yields
     * 403 or 404, never the 409 a closed thread would otherwise produce.
     */
    @Test
    void closedThreadIsNotVisibleToUnauthorizedViewer() {
        when(repository.find(ID)).thenReturn(Optional.of(closedConversation()));
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(closedConversation()));

        asReception(RECEPTION);
        ApiException wrongUnitRead = reject(() -> service.get(ID));
        assertEquals(403, wrongUnitRead.status());
        assertEquals("FORBIDDEN", wrongUnitRead.code());
        ApiException wrongUnitReply = reject(() -> service.reply(ID, new ReplyRequest("synthetic body"), 2));
        assertEquals(403, wrongUnitReply.status());
        assertEquals("FORBIDDEN", wrongUnitReply.code());

        asPatient(OTHER_PATIENT);
        ApiException wrongPatientRead = reject(() -> service.get(ID));
        assertEquals(404, wrongPatientRead.status());
        assertEquals("NOT_FOUND", wrongPatientRead.code());
        ApiException wrongPatientReply = reject(() -> service.reply(ID, new ReplyRequest("synthetic body"), 2));
        assertEquals(404, wrongPatientReply.status());
        assertEquals("NOT_FOUND", wrongPatientReply.code());

        assertNoWrites();
    }

    /** A stale version is rejected before the closed-state check is reached. */
    @Test
    void staleVersionOnClosedThreadIsRejectedBeforeTheStateCheck() {
        asLabStaff(LAB_STAFF);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(closedConversation()));

        ApiException failure = reject(() -> service.reply(ID, new ReplyRequest("synthetic body"), 1));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        assertNoWrites();
    }
}
