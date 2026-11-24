package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.messaging.conversations.ConversationActions;
import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.State;
import com.aurevia.messaging.conversations.ConversationController;
import com.aurevia.messaging.conversations.ConversationRepository;
import com.aurevia.messaging.conversations.ConversationService;
import com.aurevia.messaging.conversations.PatientConnectionVerifier;
import com.aurevia.messaging.conversations.PatientConnectionVerifier.VerifiedConnection;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-02 clause 3 (API test): every new inbound patient message creates or
 * reopens a work item.
 *
 * <p>Each case enters through the real {@link com.aurevia.messaging.conversations.ConversationController}
 * and {@link com.aurevia.messaging.conversations.ConversationService}, with a
 * mocked repository and patient-connection verifier, a synchronous transaction
 * and a synthetic tenant context. A work item is one per conversation: the
 * append-only work ledger records the transition that makes it active for staff
 * (CREATED on open, REOPENED from RESOLVED), while a reply on an already-open
 * thread keeps that same item active and returns it to awaiting staff. Every
 * refusal is asserted to leave the conversation, its message ledger and its
 * work ledger untouched. Synthetic subjects and text only.</p>
 */
class NewInboundPatientMessageCreatesReopensWorkTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String CONNECTION_ID = "33333333-3333-3333-3333-333333333333";
    private static final String CONVERSATION_ID = "44444444-4444-4444-4444-444444444444";
    private static final String PATIENT = "patient-a";
    private static final String OTHER_PATIENT = "patient-b";
    private static final String STAFF = "clinic-staff-a";
    private static final String ADMIN = "admin-a";
    private static final String TOPIC = "question about results";
    private static final String BODY = "following up on my question";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private ConversationRepository repository;
    private AuditAppender audit;
    private PatientConnectionVerifier connections;
    private MockMvc mvc;

    /** Build the real service and controller over mocked boundaries and a synchronous transaction. */
    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
        audit = mock(AuditAppender.class);
        connections = mock(PatientConnectionVerifier.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        ConversationService service = new ConversationService(repository, new ConversationActions(),
                transactions, audit, connections);
        mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    /** Remove the synthetic tenant context so other tests are unaffected. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Bind a synthetic tenant context for an authenticated subject. */
    private void as(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "CONVERSATION_CLAUSE", "corr-msg02-c3"));
    }

    /** Grant an active membership and one role to a synthetic subject. */
    private void authorize(String subject, String role) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(role));
    }

    /** Synthetic conversation projection with caller-supplied identity and lifecycle state. */
    private Conversation conversation(String id, String patientSubject, State state, int version) {
        return new Conversation(id, version, PATIENT_ID, patientSubject, "clinic", CONNECTION_ID,
                TOPIC, state, null, 0, 0, NOW);
    }

    /** Synthetic conversation projection for the fixed clause conversation. */
    private Conversation conversation(String patientSubject, State state, int version) {
        return conversation(CONVERSATION_ID, patientSubject, state, version);
    }

    /** Verify a fully active verified connection for the patient's open or reply. */
    private void stubVerifiedConnection() {
        when(connections.requireActive(PATIENT_ID, "clinic", CONNECTION_ID))
                .thenReturn(new VerifiedConnection(PATIENT_ID, CONNECTION_ID, "clinic"));
    }

    /** Stub every call a successful patient reply makes, including the fresh read that renders the view. */
    private void stubPatientReply(State currentState, int version) {
        when(repository.loadForUpdate(CONVERSATION_ID))
                .thenReturn(Optional.of(conversation(PATIENT, currentState, version)));
        when(repository.patientLinkActive(PATIENT, PATIENT_ID)).thenReturn(true);
        stubVerifiedConnection();
        when(repository.nextSequence(CONVERSATION_ID)).thenReturn(2);
        when(repository.find(CONVERSATION_ID))
                .thenReturn(Optional.of(conversation(PATIENT, State.AWAITING_STAFF, version + 1)));
        when(repository.messages(CONVERSATION_ID)).thenReturn(List.of());
        when(repository.events(CONVERSATION_ID)).thenReturn(List.of());
    }

    /** POST the synthetic open command through the real controller. */
    private ResultActions open() throws Exception {
        String json = "{\"patientId\":\"" + PATIENT_ID + "\",\"patientSubject\":\"" + PATIENT
                + "\",\"unitKey\":\"clinic\",\"connectionId\":\"" + CONNECTION_ID
                + "\",\"topic\":\"" + TOPIC + "\"}";
        return mvc.perform(post("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** POST the synthetic reply command through the real controller with an If-Match version. */
    private ResultActions reply(int version) throws Exception {
        String json = "{\"body\":\"" + BODY + "\"}";
        return mvc.perform(post("/api/v1/conversations/" + CONVERSATION_ID + "/reply")
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"" + version + "\"")
                .content(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** Assert that a refusal wrote nothing to the conversation, message or work ledgers. */
    private void assertNoConversationWrites() {
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).insertMessage(any(), anyInt(), any(), any(), any(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
        verify(repository, never()).updateState(any(), anyInt(), any());
    }

    /** The first inbound patient message creates a work item and the staff queue sees it. */
    @Test
    void patientOpenCreatesAWorkItemAndQueuesItForStaff() throws Exception {
        as(PATIENT);
        authorize(PATIENT, "patient");
        when(repository.patientExists(PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive(PATIENT, PATIENT_ID)).thenReturn(true);
        stubVerifiedConnection();
        when(repository.find(anyString())).thenAnswer(invocation -> Optional.of(
                conversation(invocation.getArgument(0), PATIENT, State.AWAITING_STAFF, 1)));

        ResultActions opened = open();
        opened.andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("awaiting_staff"));

        ArgumentCaptor<String> createdConversation = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        verify(repository).appendEvent(createdConversation.capture(), eq(1), eventType.capture(), eq(PATIENT),
                isNull(), eq("awaiting_staff"), eq("conversation opened"));
        String id = createdConversation.getValue();
        assertEquals("created", eventType.getValue());
        opened.andExpect(jsonPath("$.id").value(id));

        verify(repository).insertConversation(eq(id), eq(PATIENT_ID), eq(PATIENT), eq("clinic"),
                eq(CONNECTION_ID), eq(TOPIC), eq("awaiting_staff"));

        as(STAFF);
        authorize(STAFF, "reception");
        when(repository.workQueue(List.of("clinic")))
                .thenReturn(List.of(conversation(id, PATIENT, State.AWAITING_STAFF, 1)));
        mvc.perform(get("/api/v1/conversations/work-queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].id").value(id))
                .andExpect(jsonPath("$.entries[0].state").value("awaiting_staff"));
        verify(repository).workQueue(List.of("clinic"));
    }

    /** A patient reply on a resolved thread reopens the work item. */
    @Test
    void patientReplyToResolvedThreadReopensTheWorkItem() throws Exception {
        as(PATIENT);
        authorize(PATIENT, "patient");
        stubPatientReply(State.RESOLVED, 1);

        reply(1).andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.state").value("awaiting_staff"));

        verify(connections).requireActive(PATIENT_ID, "clinic", CONNECTION_ID);
        verify(repository).insertMessage(CONVERSATION_ID, 2, "patient", PATIENT, "patient", BODY);
        verify(repository).updateState(CONVERSATION_ID, 1, "awaiting_staff");
        verify(repository).appendEvent(CONVERSATION_ID, 2, "reopened", PATIENT,
                "resolved", "awaiting_staff", "reply reopened the work item");
    }

    /** A reply on an open thread keeps the same work item active and awaiting staff; no second work fact is needed. */
    @Test
    void patientReplyWhileAwaitingPatientKeepsTheWorkItemActiveAwaitingStaff() throws Exception {
        as(PATIENT);
        authorize(PATIENT, "patient");
        stubPatientReply(State.AWAITING_PATIENT, 1);

        reply(1).andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.state").value("awaiting_staff"));

        verify(repository).insertMessage(CONVERSATION_ID, 2, "patient", PATIENT, "patient", BODY);
        verify(repository).updateState(CONVERSATION_ID, 1, "awaiting_staff");
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    /** A revoked patient link cannot reopen even a resolved thread. */
    @Test
    void patientReplyIsRefusedWhenThePatientLinkIsInactive() throws Exception {
        as(PATIENT);
        authorize(PATIENT, "patient");
        when(repository.loadForUpdate(CONVERSATION_ID))
                .thenReturn(Optional.of(conversation(PATIENT, State.RESOLVED, 1)));
        when(repository.patientLinkActive(PATIENT, PATIENT_ID)).thenReturn(false);

        reply(1).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertNoConversationWrites();
    }

    /** Revoking the exact connection stops a new message even on a current thread. */
    @Test
    void patientReplyIsRefusedWhenTheVerifiedConnectionIsRevoked() throws Exception {
        as(PATIENT);
        authorize(PATIENT, "patient");
        when(repository.loadForUpdate(CONVERSATION_ID))
                .thenReturn(Optional.of(conversation(PATIENT, State.AWAITING_STAFF, 1)));
        when(repository.patientLinkActive(PATIENT, PATIENT_ID)).thenReturn(true);
        when(connections.requireActive(PATIENT_ID, "clinic", CONNECTION_ID))
                .thenThrow(new ApiException(403, "FORBIDDEN", "connection is no longer active"));

        reply(1).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertNoConversationWrites();
        verify(connections).requireActive(PATIENT_ID, "clinic", CONNECTION_ID);
    }

    /** A closed thread is read-only, so no reply can reach the work ledger. */
    @Test
    void patientReplyIsRefusedOnAClosedThread() throws Exception {
        as(PATIENT);
        authorize(PATIENT, "patient");
        when(repository.loadForUpdate(CONVERSATION_ID))
                .thenReturn(Optional.of(conversation(PATIENT, State.CLOSED, 1)));

        reply(1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSED_THREAD"));

        assertNoConversationWrites();
    }

    /** A version that is not the one read is refused before any message or work fact is appended. */
    @Test
    void patientReplyIsRefusedWithAStaleVersion() throws Exception {
        as(PATIENT);
        authorize(PATIENT, "patient");
        when(repository.loadForUpdate(CONVERSATION_ID))
                .thenReturn(Optional.of(conversation(PATIENT, State.AWAITING_STAFF, 2)));

        reply(1).andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        assertNoConversationWrites();
    }

    /** A non-clinical role holds no reply permission and its denial is audited. */
    @Test
    void replyByAnUnauthorisedRoleIsRefused() throws Exception {
        as(ADMIN);
        authorize(ADMIN, "tenant_admin");

        reply(1).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertNoConversationWrites();
        verify(audit).append(any(), eq("CONVERSATION_REPLY"), eq("CONVERSATION_REPLY"),
                eq("CONVERSATION"), eq("CONVERSATION"), eq(CONVERSATION_ID), eq("DENIED"));
    }

    /** Another patient's thread is concealed as not found rather than exposed as forbidden. */
    @Test
    void otherPatientsThreadIsConcealed() throws Exception {
        as(PATIENT);
        authorize(PATIENT, "patient");
        when(repository.loadForUpdate(CONVERSATION_ID))
                .thenReturn(Optional.of(conversation(OTHER_PATIENT, State.AWAITING_STAFF, 1)));

        reply(1).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertNoConversationWrites();
    }
}
