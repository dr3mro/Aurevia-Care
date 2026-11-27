package com.aurevia.it.messagingconversations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.aurevia.api.contract.IdempotencyKey;
import com.aurevia.audit.AuditAppender;
import com.aurevia.messaging.conversations.ConversationActions;
import com.aurevia.messaging.conversations.ConversationContract.AssignRequest;
import com.aurevia.messaging.conversations.ConversationContract.CloseRequest;
import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.Message;
import com.aurevia.messaging.conversations.ConversationContract.OpenRequest;
import com.aurevia.messaging.conversations.ConversationContract.ReplyRequest;
import com.aurevia.messaging.conversations.ConversationContract.ResolveRequest;
import com.aurevia.messaging.conversations.ConversationContract.SenderKind;
import com.aurevia.messaging.conversations.ConversationContract.State;
import com.aurevia.messaging.conversations.ConversationContract.TransferRequest;
import com.aurevia.messaging.conversations.ConversationContract.View;
import com.aurevia.messaging.conversations.ConversationContract.WorkItemEvent;
import com.aurevia.messaging.conversations.ConversationRepository;
import com.aurevia.messaging.conversations.ConversationService;
import com.aurevia.messaging.conversations.ConversationUnitResolver;
import com.aurevia.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.messaging.units.CareUnitDirectoryService;
import com.aurevia.messaging.units.InMemoryMessagingStore;
import com.aurevia.messaging.units.MessagingActions;
import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.tenant.TenantContext;

/**
 * Fast, database-free acceptance for the MSG-02 domain service. Real repository
 * SQL, tenant policy and HTTP binding are covered by {@link ConversationFlowTest}.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String CONNECTION_ID = "44444444-4444-4444-4444-444444444444";
    private static final String OTHER_PATIENT = "55555555-5555-5555-5555-555555555555";
    private static final String UNKNOWN_CONNECTION_ID = "66666666-6666-6666-6666-666666666666";
    private static final String REVOKED_CONNECTION_ID = "77777777-7777-7777-7777-777777777777";
    private static final String FOREIGN_CONNECTION_ID = "88888888-8888-8888-8888-888888888888";
    private static final String RADIOLOGY_CONNECTION_ID = "99999999-9999-9999-9999-999999999999";
    private static final String LAB_UNIT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String RADIOLOGY_UNIT_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse("reply-key-1");

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
     * transaction template that executes its callback synchronously. The
     * messaging store is deliberately real so connection verification is proven
     * at the domain boundary rather than stubbed.
     */
    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        unitStore = new InMemoryMessagingStore();
        CareUnitDirectoryService directory = new CareUnitDirectoryService(unitStore, new MessagingActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ConversationService(repository, new ConversationActions(), transactions, audit, directory);
        lenient().when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        lenient().when(repository.findMessageByClientId(any(), any())).thenReturn(Optional.empty());
        lenient().when(repository.insertMessageIfAbsent(any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        bind("patient-a");
        unitStore.addMember(ORG, "patient-a", "patient", true);
        unitStore.addPatient(ORG, PATIENT_ID);
        unitStore.linkPatient(ORG, "patient-a", PATIENT_ID);
        unitStore.addUnit(new CareUnit(LAB_UNIT_ID, ORG, "facility-1", "Synthetic Lab",
                List.of("results"), true, "lab"));
        unitStore.addUnit(new CareUnit(RADIOLOGY_UNIT_ID, ORG, "facility-1", "Synthetic Radiology",
                List.of("imaging"), true, "radiology"));
        seedConnection(CONNECTION_ID, PATIENT_ID, UnitKey.LAB, ConnectionState.ACTIVE);
    }

    /** Clear the thread-local tenant context. */
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

    private static Conversation conversation(State state, int version, String patientSubject,
                                             String unitKey, String assignee) {
        return new Conversation(ID, version, PATIENT_ID, patientSubject, unitKey, CONNECTION_ID,
                "topic", state, assignee, 0, 0, NOW);
    }

    private void seedConnection(String id, String patientId, UnitKey unit, ConnectionState state) {
        String unitId = unit == UnitKey.LAB ? LAB_UNIT_ID : RADIOLOGY_UNIT_ID;
        unitStore.insertConnection(ORG, new PatientUnitConnection(id, ORG, patientId,
                unitId, ConnectionContext.ORDER,
                "order-ref-" + id, "rel-" + id, state, "patient-a", NOW, NOW, null));
    }

    private static Message message(int sequence, SenderKind kind) {
        return new Message(ID, ID, sequence, kind, "sender", "role", "body", NOW);
    }

    @Test
    void patientOpenCreatesAwaitingStaffConversationAndWorkItem() {
        asPatient("patient-a");
        seedConnection(CONNECTION_ID, PATIENT_ID, UnitKey.LAB, ConnectionState.ACTIVE);
        when(repository.patientExists(PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.find(any())).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));

        Conversation created = service.open(new OpenRequest(PATIENT_ID, "patient-a", "lab", CONNECTION_ID, " topic "));

        assertEquals(State.AWAITING_STAFF, created.state());
        verify(repository).insertConversation(any(), eq(PATIENT_ID), eq("patient-a"), eq("lab"),
                eq(CONNECTION_ID), eq("topic"), eq("awaiting_staff"));
        verify(repository).appendEvent(any(), eq(1), eq("created"), eq("patient-a"), any(),
                eq("awaiting_staff"), any());
        verify(audit).append(any(), eq("CONVERSATION_OPEN"), eq("CONVERSATION_OPEN"),
                eq("CONVERSATION"), eq("CONVERSATION"), any(), eq("SUCCESS"));
    }

    @Test
    void staffOpenCreatesAwaitingPatientConversation() {
        asStaff("lab-a", "lab_verifier");
        when(repository.patientExists(PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.find(any())).thenReturn(Optional.of(conversation(State.AWAITING_PATIENT, 1, "patient-a", "lab", null)));

        Conversation created = service.open(new OpenRequest(PATIENT_ID, "patient-a", "lab", CONNECTION_ID, null));

        assertEquals(State.AWAITING_PATIENT, created.state());
        verify(repository).insertConversation(any(), eq(PATIENT_ID), eq("patient-a"), eq("lab"),
                eq(CONNECTION_ID), isNull(), eq("awaiting_patient"));
    }

    @Test
    void openRejectsMissingPatient() {
        asPatient("patient-a");
        when(repository.patientExists(PATIENT_ID)).thenReturn(false);
        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT_ID, "patient-a", "lab", CONNECTION_ID, null)));
        assertEquals(404, failure.status());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void openRejectsMissingVerifiedLink() {
        asPatient("patient-a");
        when(repository.patientExists(PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(false);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT_ID, "patient-a", "lab", CONNECTION_ID, null))).status());
    }

    @Test
    void openConcealsAnotherSubjectWithoutProbingPatientExistence() {
        asPatient("patient-a");
        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT_ID, "patient-b", "lab", CONNECTION_ID, null)));
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).patientExists(any());
        verify(repository, never()).patientLinkActive(any(), any());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void openRejectsStaffOfWrongUnit() {
        asStaff("lab-a", "lab_verifier");
        when(repository.patientExists(PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT_ID, "patient-a", "clinic", CONNECTION_ID, null))).status());
    }

    @Test
    void patientOpenWithoutAnyVerifiedConnectionIsDenied() {
        asPatient("patient-a");
        when(repository.patientExists(PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT_ID, "patient-a", "lab", UNKNOWN_CONNECTION_ID, null)));
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void patientOpenWithAConnectionToAnotherUnitIsDenied() {
        asPatient("patient-a");
        when(repository.patientExists(PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        ApiException failure = assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT_ID, "patient-a", "radiology", CONNECTION_ID, null)));
        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void patientOpenWithARevokedConnectionIsDenied() {
        asPatient("patient-a");
        seedConnection(REVOKED_CONNECTION_ID, PATIENT_ID, UnitKey.LAB, ConnectionState.REVOKED);
        when(repository.patientExists(PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT_ID, "patient-a", "lab", REVOKED_CONNECTION_ID, null))).status());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void patientOpenWithAConnectionBoundToAnotherPatientIsDenied() {
        asPatient("patient-a");
        seedConnection(FOREIGN_CONNECTION_ID, OTHER_PATIENT, UnitKey.LAB, ConnectionState.ACTIVE);
        when(repository.patientExists(PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT_ID, "patient-a", "lab", FOREIGN_CONNECTION_ID, null))).status());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void patientOpenWithAMalformedConnectionIdIsRejectedBeforeAnyWrite() {
        asPatient("patient-a");
        when(repository.patientExists(PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.open(new OpenRequest(PATIENT_ID, "patient-a", "lab", "conn-1", null))).status());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void patientReplyAfterTheConnectionWasRevokedIsDenied() {
        asPatient("patient-a");
        seedConnection(REVOKED_CONNECTION_ID, PATIENT_ID, UnitKey.LAB, ConnectionState.REVOKED);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(new Conversation(
                ID, 1, PATIENT_ID, "patient-a", "lab", REVOKED_CONNECTION_ID,
                "topic", State.AWAITING_PATIENT, null, 0, 0, NOW)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1, KEY)).status());
        verify(repository, never()).insertMessageIfAbsent(any(), anyInt(), any(), any(), any(), any(), any());
        verify(repository, never()).updateState(any(), anyInt(), any());
    }

    @Test
    void identicalClientIdReplaysTheOriginalMessageWithoutAWrite() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(
                conversation(State.AWAITING_STAFF, 2, "patient-a", "lab", null)));
        when(repository.findMessageByClientId(ID, KEY.value())).thenReturn(Optional.of(
                new Message(ID, ID, 1, SenderKind.PATIENT, "patient-a", "patient", "hello", NOW)));
        stubView(State.AWAITING_STAFF, 2, List.of(message(1, SenderKind.PATIENT)));

        View view = service.reply(ID, new ReplyRequest("hello"), 1, KEY);

        assertEquals(State.AWAITING_STAFF, view.conversation().state());
        verify(repository, never()).insertMessageIfAbsent(any(), anyInt(), any(), any(), any(), any(), any());
        verify(repository, never()).updateState(any(), anyInt(), any());
    }

    @Test
    void reusedClientIdWithADifferentBodyConflicts() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(
                conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        when(repository.findMessageByClientId(ID, KEY.value())).thenReturn(Optional.of(
                new Message(ID, ID, 1, SenderKind.PATIENT, "patient-a", "patient", "first", NOW)));

        assertEquals(409, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("second"), 1, KEY)).status());
        verify(repository, never()).insertMessageIfAbsent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void concurrentDuplicateClientIdReturnsOriginalInsteadOfAppending() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(
                conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.nextSequence(ID)).thenReturn(2);
        when(repository.insertMessageIfAbsent(eq(ID), eq(2), any(), any(), any(), eq("hello"), eq(KEY.value())))
                .thenReturn(false);
        when(repository.findMessageByClientId(ID, KEY.value())).thenReturn(Optional.empty(), Optional.of(
                new Message(ID, ID, 2, SenderKind.PATIENT, "patient-a", "patient", "hello", NOW)));
        stubView(State.AWAITING_STAFF, 2, List.of(message(2, SenderKind.PATIENT)));

        View view = service.reply(ID, new ReplyRequest("hello"), 1, KEY);

        assertEquals(2, view.conversation().version());
        verify(repository, never()).updateState(any(), anyInt(), any());
    }

    @Test
    void concurrentDuplicateWithNoStoredMessageConflicts() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(
                conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.nextSequence(ID)).thenReturn(2);
        when(repository.insertMessageIfAbsent(eq(ID), eq(2), any(), any(), any(), eq("hello"), eq(KEY.value())))
                .thenReturn(false);
        when(repository.findMessageByClientId(ID, KEY.value()))
                .thenReturn(Optional.empty(), Optional.empty());

        assertEquals(409, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1, KEY)).status());
        verify(repository, never()).updateState(any(), anyInt(), any());
    }

    @Test
    void patientReplyRejectsAConnectionBoundToAnotherUnit() {
        asPatient("patient-a");
        seedConnection(RADIOLOGY_CONNECTION_ID, PATIENT_ID, UnitKey.RADIOLOGY, ConnectionState.ACTIVE);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(new Conversation(
                ID, 1, PATIENT_ID, "patient-a", "lab", RADIOLOGY_CONNECTION_ID,
                "topic", State.AWAITING_PATIENT, null, 0, 0, NOW)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1, KEY)).status());
        verify(repository, never()).insertMessageIfAbsent(any(), anyInt(), any(), any(), any(), any(), any());
        verify(repository, never()).updateState(any(), anyInt(), any());
    }

    @Test
    void patientReplyAppendsMessageAndMovesToAwaitingStaff() {
        asPatient("patient-a");
        seedConnection(CONNECTION_ID, PATIENT_ID, UnitKey.LAB, ConnectionState.ACTIVE);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_PATIENT, 1, "patient-a", "lab", null)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.nextSequence(ID)).thenReturn(2);
        stubView(State.AWAITING_STAFF, 2, List.of());

        View view = service.reply(ID, new ReplyRequest(" hello "), 1, KEY);

        assertEquals(State.AWAITING_STAFF, view.conversation().state());
        verify(repository).insertMessageIfAbsent(ID, 2, "patient", "patient-a", "patient", "hello", KEY.value());
        verify(repository).updateState(ID, 1, "awaiting_staff");
        verify(repository, never()).appendEvent(eq(ID), eq(2), eq("reopened"), any(), any(), any(), any());
    }

    @Test
    void staffReplyAppendsMessageAndMovesToAwaitingPatient() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.nextSequence(ID)).thenReturn(3);
        stubView(State.AWAITING_PATIENT, 2, List.of());

        View view = service.reply(ID, new ReplyRequest("please arrive early"), 1, KEY);

        assertEquals(State.AWAITING_PATIENT, view.conversation().state());
        verify(repository).insertMessageIfAbsent(ID, 3, "staff", "lab-a", "lab_verifier", "please arrive early", KEY.value());
    }

    @Test
    void staffReplyCannotReopenAResolvedThreadWhenTheVerifiedLinkIsRevoked() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.RESOLVED, 2, "patient-a", "lab", null)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 2, KEY));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).insertMessageIfAbsent(any(), anyInt(), any(), any(), any(), any(), any());
        verify(repository, never()).updateState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void staffReplyReopensAResolvedThreadWhenTheVerifiedLinkIsActive() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.RESOLVED, 2, "patient-a", "lab", null)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.nextSequence(ID)).thenReturn(3);
        stubView(State.AWAITING_PATIENT, 3, List.of());

        service.reply(ID, new ReplyRequest("one more answer"), 2, KEY);

        verify(repository).appendEvent(eq(ID), eq(3), eq("reopened"), eq("lab-a"),
                eq("resolved"), eq("awaiting_patient"), any());
    }

    @Test
    void replyToResolvedThreadReopensWorkItem() {
        asPatient("patient-a");
        seedConnection(CONNECTION_ID, PATIENT_ID, UnitKey.LAB, ConnectionState.ACTIVE);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.RESOLVED, 2, "patient-a", "lab", null)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(true);
        when(repository.nextSequence(ID)).thenReturn(4);
        stubView(State.AWAITING_STAFF, 3, List.of());

        service.reply(ID, new ReplyRequest("one more question"), 2, KEY);

        verify(repository).appendEvent(eq(ID), eq(3), eq("reopened"), eq("patient-a"),
                eq("resolved"), eq("awaiting_staff"), any());
    }

    @Test
    void replyRejectsRevokedPatientLink() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.RESOLVED, 2, "patient-a", "lab", null)));
        when(repository.patientLinkActive("patient-a", PATIENT_ID)).thenReturn(false);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 2, KEY)).status());
    }

    @Test
    void replyRejectsClosedThread() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.CLOSED, 2, "patient-a", "lab", null)));
        ApiException failure = assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 2, KEY));
        assertEquals(409, failure.status());
        assertEquals("CLOSED_THREAD", failure.code());
    }

    @Test
    void replyRejectsStaleVersion() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 2, "patient-a", "lab", null)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1, KEY)).status());
    }

    @Test
    void replyRejectsPatientAccessToAnotherThread() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-b", "lab", null)));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1, KEY)).status());
    }

    @Test
    void replyRejectsStaffOfWrongUnit() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "clinic", null)));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1, KEY)).status());
    }

    @Test
    void replyRejectsUnknownConversation() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1, KEY)).status());
    }

    @Test
    void replyRejectsBlankBodyBeforeAnyWrite() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("  "), 1, KEY)).status());
        verify(repository, never()).loadForUpdate(any());
    }

    @Test
    void assignAndTransferUseEligibleSameUnitStaffWithReason() {
        asStaff("lab-a", "lab_verifier");
        when(repository.hasActiveMembership("lab-b")).thenReturn(true);
        when(repository.activeRoles("lab-b")).thenReturn(List.of("lab_verifier"));
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        when(repository.find(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 2, "patient-a", "lab", "lab-b")));

        Conversation assigned = service.assign(ID, new AssignRequest("lab-b", " cover "), 1);
        assertEquals("lab-b", assigned.assigneeSubject());
        verify(repository).appendEvent(eq(ID), eq(2), eq("assigned"), eq("lab-a"),
                eq("awaiting_staff"), eq("awaiting_staff"), eq("cover"));

        service.transfer(ID, new TransferRequest("lab-b", " handover "), 1);
        verify(repository).appendEvent(eq(ID), eq(2), eq("transferred"), eq("lab-a"),
                eq("awaiting_staff"), eq("awaiting_staff"), eq("handover"));
    }

    @Test
    void assignRejectsInactiveTarget() {
        asStaff("lab-a", "lab_verifier");
        when(repository.hasActiveMembership("lab-b")).thenReturn(false);
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.assign(ID, new AssignRequest("lab-b", "cover"), 1)).status());
    }

    @Test
    void assignRejectsTargetOfWrongUnit() {
        asStaff("lab-a", "lab_verifier");
        when(repository.hasActiveMembership("clinician-b")).thenReturn(true);
        when(repository.activeRoles("clinician-b")).thenReturn(List.of("clinician"));
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.assign(ID, new AssignRequest("clinician-b", "cover"), 1)).status());
    }

    @Test
    void assignRejectsNonStaffTargetRole() {
        asStaff("lab-a", "lab_verifier");
        when(repository.hasActiveMembership("admin-b")).thenReturn(true);
        when(repository.activeRoles("admin-b")).thenReturn(List.of("tenant_admin"));
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.assign(ID, new AssignRequest("admin-b", "cover"), 1)).status());
    }

    @Test
    void assignRejectsCallerOfWrongUnitAndClosedThreadAndStaleVersion() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "clinic", null)),
                Optional.of(conversation(State.CLOSED, 1, "patient-a", "lab", null)),
                Optional.of(conversation(State.AWAITING_STAFF, 2, "patient-a", "lab", null)));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.assign(ID, new AssignRequest("lab-b", "cover"), 1)).status());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.assign(ID, new AssignRequest("lab-b", "cover"), 1)).status());
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.assign(ID, new AssignRequest("lab-b", "cover"), 1)).status());
    }

    @Test
    void assignRejectsBlankReason() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.assign(ID, new AssignRequest("lab-b", " "), 1)).status());
    }

    @Test
    void resolveAndClosePersistReasonAndState() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)),
                Optional.of(conversation(State.RESOLVED, 2, "patient-a", "lab", null)));
        when(repository.find(ID)).thenReturn(Optional.of(conversation(State.RESOLVED, 2, "patient-a", "lab", null)));

        Conversation resolved = service.resolve(ID, new ResolveRequest(" answered "), 1);
        assertEquals(State.RESOLVED, resolved.state());
        verify(repository).updateState(ID, 1, "resolved");
        verify(repository).appendEvent(eq(ID), eq(2), eq("resolved"), eq("lab-a"),
                eq("awaiting_staff"), eq("resolved"), eq("answered"));

        service.close(ID, new CloseRequest(" done "), 2);
        verify(repository).updateState(ID, 2, "closed");
        verify(repository).appendEvent(eq(ID), eq(3), eq("closed"), eq("lab-a"),
                eq("resolved"), eq("closed"), eq("done"));
    }

    @Test
    void closeRejectsAlreadyClosedThreadAndResolveRejectsBlankReason() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.CLOSED, 2, "patient-a", "lab", null)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.close(ID, new CloseRequest("done"), 2)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.resolve(ID, new ResolveRequest(" "), 1)).status());
    }

    @Test
    void patientMarkReadAdvancesUnreadWatermarkFromStaffMessages() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        when(repository.messages(ID)).thenReturn(List.of(message(1, SenderKind.PATIENT),
                message(2, SenderKind.STAFF), message(3, SenderKind.STAFF)));
        when(repository.find(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 2, "patient-a", "lab", null)));

        Conversation updated = service.markRead(ID, 1);

        assertEquals(2, updated.version());
        verify(repository).markPatientRead(ID, 1, 3);
        verify(repository, never()).markStaffRead(any(), anyInt(), anyInt());
    }

    @Test
    void staffMarkReadUsesPatientMessagesAndZeroWhenNoneExist() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)),
                Optional.of(conversation(State.AWAITING_STAFF, 2, "patient-a", "lab", null)));
        when(repository.messages(ID)).thenReturn(List.of(message(1, SenderKind.PATIENT),
                message(2, SenderKind.STAFF)), List.of());
        when(repository.find(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 3, "patient-a", "lab", null)));

        service.markRead(ID, 1);
        verify(repository).markStaffRead(ID, 1, 1);

        service.markRead(ID, 2);
        verify(repository).markStaffRead(ID, 2, 0);
    }

    @Test
    void markReadRejectsStaleVersion() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 2, "patient-a", "lab", null)));
        assertEquals(412, assertThrows(ApiException.class, () -> service.markRead(ID, 1)).status());
    }

    @Test
    void getReturnsThreadWithBothTrails() {
        asStaff("lab-a", "lab_verifier");
        when(repository.find(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        when(repository.messages(ID)).thenReturn(List.of(message(1, SenderKind.PATIENT)));
        WorkItemEvent event = new WorkItemEvent(ID, ID, 1, com.aurevia.messaging.conversations
                .ConversationContract.WorkEventType.CREATED, "patient-a", null, "awaiting_staff",
                "opened", NOW);
        when(repository.events(ID)).thenReturn(List.of(event));

        View view = service.get(ID);

        assertNotNull(view.conversation());
        assertEquals(1, view.messages().size());
        assertEquals(1, view.events().size());
    }

    @Test
    void getConcealsAnotherPatientsThread() {
        asPatient("patient-a");
        when(repository.find(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, "patient-b", "lab", null)));
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(ID)).status());
    }

    @Test
    void inboxReturnsPatientEntries() {
        asPatient("patient-a");
        when(repository.inbox("patient-a")).thenReturn(List.of(
                new com.aurevia.messaging.conversations.ConversationContract.InboxEntry(
                        conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null), 2)));
        assertEquals(2, service.inbox().entries().get(0).unreadCount());
    }

    @Test
    void workQueueIsScopedToTheCallersUnits() {
        asStaff("lab-a", "lab_verifier");
        when(repository.workQueue(List.of("lab"))).thenReturn(List.of(
                conversation(State.AWAITING_STAFF, 1, "patient-a", "lab", null)));
        assertEquals(1, service.workQueue().entries().size());
        verify(repository).workQueue(List.of("lab"));
    }

    @Test
    void workQueueForAdminCoversEveryUnit() {
        asStaff("admin-a", "tenant_admin");
        when(repository.workQueue(List.of("clinic", "lab", "radiology", "pt", "pharmacy")))
                .thenReturn(List.of());
        assertEquals(0, service.workQueue().entries().size());
        verify(repository).workQueue(List.of("clinic", "lab", "radiology", "pt", "pharmacy"));
    }

    @Test
    void tenantAdminCannotWriteAWorkItem() {
        asStaff("admin-a", "tenant_admin");
        ApiException assignFailure = assertThrows(ApiException.class,
                () -> service.assign(ID, new AssignRequest("lab-b", "cover"), 1));
        assertEquals(403, assignFailure.status());
        ApiException replyFailure = assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1, KEY));
        assertEquals(403, replyFailure.status());
        verify(repository, never()).loadForUpdate(any());
        verify(audit).append(any(), eq("WORK_ASSIGN"), eq("WORK_ASSIGN"),
                eq("CONVERSATION"), eq("CONVERSATION"), eq(ID), eq("DENIED"));
    }

    @Test
    void aForeignThreadIsConcealedBeforeVersionAndStateChecks() {
        asPatient("patient-a");
        when(repository.loadForUpdate(ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, 2, "patient-b", "lab", null)),
                Optional.of(conversation(State.CLOSED, 2, "patient-b", "lab", null)));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1, KEY)).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 2, KEY)).status());
    }

    @Test
    void aWrongUnitStaffMemberIsDeniedBeforeVersionCheck() {
        asStaff("lab-a", "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, 2, "patient-a", "clinic", null)));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.reply(ID, new ReplyRequest("hello"), 1, KEY)).status());
    }

    @Test
    void aMalformedConversationIdIsRejectedBeforeAnyRepositoryCall() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.get("not-a-uuid")).status());
        verify(repository, never()).find(any());
    }

    @Test
    void deniedMembershipIsAuditedThenRejected() {
        when(repository.hasActiveMembership("patient-a")).thenReturn(false);
        ApiException failure = assertThrows(ApiException.class, () -> service.inbox());
        assertEquals(403, failure.status());
        verify(audit).append(any(), eq("INBOX_READ"), eq("INBOX_READ"),
                eq("CONVERSATION"), eq("CONVERSATION"), eq("INBOX"), eq("DENIED"));
    }

    @Test
    void deniedPermissionIsAuditedThenRejected() {
        bind("patient-a");
        when(repository.hasActiveMembership("patient-a")).thenReturn(true);
        when(repository.activeRoles("patient-a")).thenReturn(List.of("patient"));
        ApiException failure = assertThrows(ApiException.class,
                () -> service.assign(ID, new AssignRequest("lab-b", "cover"), 1));
        assertEquals(403, failure.status());
        verify(audit).append(any(), eq("WORK_ASSIGN"), eq("WORK_ASSIGN"),
                eq("CONVERSATION"), eq("CONVERSATION"), eq(ID), eq("DENIED"));
    }

    private void stubView(State state, int version, List<Message> messages) {
        when(repository.find(ID)).thenReturn(Optional.of(conversation(state, version, "patient-a", "lab", null)));
        when(repository.messages(ID)).thenReturn(messages);
        when(repository.events(ID)).thenReturn(List.of());
    }
}
