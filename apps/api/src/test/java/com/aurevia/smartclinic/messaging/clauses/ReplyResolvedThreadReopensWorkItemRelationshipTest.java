package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.messaging.conversations.ConversationActions;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.Message;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.ReplyRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.State;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.View;
import com.aurevia.smartclinic.messaging.conversations.ConversationRepository;
import com.aurevia.smartclinic.messaging.conversations.ConversationService;
import com.aurevia.smartclinic.messaging.units.CareUnitDirectoryService;
import com.aurevia.smartclinic.messaging.units.InMemoryMessagingStore;
import com.aurevia.smartclinic.messaging.units.MessagingActions;
import com.aurevia.smartclinic.messaging.units.MessagingContract.CareUnit;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.smartclinic.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * MSG-02 clause 6 proof: a patient reply to a resolved thread reopens the work
 * item only while the verified patient relationship still permits messaging.
 * Five active, synthetic-fixture tests assert the reopened work-item fact and
 * state, and that a revoked link, a revoked connection, a closed thread or a
 * stale version each refuse to reopen. The service is exercised through its
 * normal entry point with a real connection directory; real PostgreSQL and HTTP
 * evidence belongs to the messaging integration suites. No production code is
 * changed here.
 */
@ExtendWith(MockitoExtension.class)
class ReplyResolvedThreadReopensWorkItemRelationshipTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String CONNECTION_ID = "44444444-4444-4444-4444-444444444444";
    private static final String REVOKED_CONNECTION_ID = "77777777-7777-7777-7777-777777777777";
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
     * Build the service with the real action and connection matrices and a
     * transaction template that runs its callback synchronously. The store is
     * real so connection state is verified at the domain boundary, not stubbed.
     */
    @BeforeEach
    void setUp() {
        unitStore = new InMemoryMessagingStore();
        CareUnitDirectoryService directory = new CareUnitDirectoryService(unitStore, new MessagingActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ConversationService(repository, new ConversationActions(), transactions, audit, directory);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        bind("patient-a");
        unitStore.addMember(ORG, "patient-a", "patient", true);
        unitStore.addPatient(ORG, PATIENT_ID);
        unitStore.linkPatient(ORG, "patient-a", PATIENT_ID);
        unitStore.addUnit(new CareUnit(LAB_UNIT_ID, ORG, "facility-1", "Synthetic Lab",
                List.of("results"), true, "lab"));
        seedConnection(CONNECTION_ID, PATIENT_ID, UnitKey.LAB, ConnectionState.ACTIVE);
    }

    /** Clear the thread-local tenant context after each test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Bind the synthetic tenant context to one patient subject. */
    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "PURPOSE", "corr-reopen-clause"));
    }

    /** Grant a synthetic subject active membership and the patient role at the repository boundary. */
    private void asPatient(String subject) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("patient"));
    }

    /** A resolved conversation bound to the active synthetic connection. */
    private static Conversation conversation(State state, int version, String patientSubject,
                                             String unitKey, String assignee) {
        return new Conversation(ID, version, PATIENT_ID, patientSubject, unitKey, CONNECTION_ID,
                "topic", state, assignee, 0, 0, NOW);
    }

    /** Insert a synthetic patient-unit connection with the requested state. */
    private void seedConnection(String id, String patientId, UnitKey unit, ConnectionState state) {
        unitStore.insertConnection(ORG, new PatientUnitConnection(id, ORG, patientId,
                LAB_UNIT_ID, ConnectionContext.ORDER,
                "order-ref-" + id, "rel-" + id, state, "patient-a", NOW, NOW, null));
    }

    /** Stub the read side of a reply so the returned view can be asserted. */
    private void stubView(State state, int version, List<Message> messages) {
        when(repository.find(ID)).thenReturn(Optional.of(conversation(state, version, "patient-a", "lab", null)));
        when(repository.messages(ID)).thenReturn(messages);
        when(repository.events(ID)).thenReturn(List.of());
    }

    @Test
    void patientReplyToResolvedThreadReopensWorkItemWhenLinkAndConnectionAreActive() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(
                conversation(State.RESOLVED, 2, "patient-a", "lab", null)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.nextSequence(ID)).thenReturn(4);
        stubView(State.AWAITING_STAFF, 3, List.of());

        View view = service.reply(ID, new ReplyRequest("one more question"), 2);

        assertEquals(State.AWAITING_STAFF, view.conversation().state());
        verify(repository).updateState(ID, 2, "awaiting_staff");
        verify(repository).appendEvent(eq(ID), eq(3), eq("reopened"), eq("patient-a"),
                eq("resolved"), eq("awaiting_staff"), any());
    }

    @Test
    void replyWhenVerifiedLinkIsRevokedDoesNotReopen() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(
                conversation(State.RESOLVED, 2, "patient-a", "lab", null)));
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
    void patientReplyWhenConnectionIsRevokedDoesNotReopen() {
        asPatient("patient-a");
        seedConnection(REVOKED_CONNECTION_ID, PATIENT_ID, UnitKey.LAB, ConnectionState.REVOKED);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(new Conversation(
                ID, 2, PATIENT_ID, "patient-a", "lab", REVOKED_CONNECTION_ID,
                "topic", State.RESOLVED, null, 0, 0, NOW)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 2));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).insertMessage(any(), anyInt(), any(), any(), any(), any());
        verify(repository, never()).updateState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void replyToClosedThreadNeverReopens() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(
                conversation(State.CLOSED, 2, "patient-a", "lab", null)));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 2));

        assertEquals(409, failure.status());
        assertEquals("CLOSED_THREAD", failure.code());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void replyWithStaleVersionDoesNotReopen() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(
                conversation(State.RESOLVED, 2, "patient-a", "lab", null)));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        verify(repository, never()).insertMessage(any(), anyInt(), any(), any(), any(), any());
        verify(repository, never()).updateState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }
}
