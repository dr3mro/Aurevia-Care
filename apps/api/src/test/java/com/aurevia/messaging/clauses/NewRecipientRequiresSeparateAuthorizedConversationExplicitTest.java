package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.messaging.conversations.ConversationActions;
import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.OpenRequest;
import com.aurevia.messaging.conversations.ConversationContract.State;
import com.aurevia.messaging.conversations.ConversationContract.TransferRequest;
import com.aurevia.messaging.conversations.ConversationRepository;
import com.aurevia.messaging.conversations.ConversationService;
import com.aurevia.messaging.conversations.PatientConnectionVerifier;
import com.aurevia.messaging.conversations.PatientConnectionVerifier.VerifiedConnection;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-01 clause 5 (API test): a new recipient requires a separate authorized
 * conversation and explicit disclosure.
 *
 * <p>Every case enters through a real domain command on
 * {@link com.aurevia.messaging.conversations.ConversationService},
 * the same service the thin
 * {@link com.aurevia.messaging.conversations.ConversationController}
 * calls. Dependencies are mocked, but the real bounded action matrix and a
 * synchronous tenant-guarded transaction run the actual guarded callback, so a
 * refusal is proven to leave the conversation, its ledgers and its success audit
 * untouched. All identifiers and subjects are synthetic.</p>
 */
class NewRecipientRequiresSeparateAuthorizedConversationExplicitTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT_SUBJECT = "patient-a";
    private static final String OTHER_PATIENT_SUBJECT = "patient-b";
    private static final String CLIENT_PATIENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String CLIENT_CONNECTION_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String VERIFIED_PATIENT_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String VERIFIED_CONNECTION_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String CONVERSATION_ID = "33333333-3333-3333-3333-333333333333";
    private static final String STAFF_SUBJECT = "clinic-staff-a";
    private static final String LAB_STAFF_SUBJECT = "lab-staff-b";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private ConversationRepository repository;
    private AuditAppender audit;
    private PatientConnectionVerifier connections;
    private ConversationService service;

    /** Build the service with mocked boundaries and a synchronous guarded transaction. */
    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
        audit = mock(AuditAppender.class);
        connections = mock(PatientConnectionVerifier.class);
        service = new ConversationService(repository, new ConversationActions(), transactions(), audit, connections);
        TenantContext.set(new TenantContext(ORG, PATIENT_SUBJECT, "CONVERSATION", "corr-new-recipient"));
    }

    /** Remove the synthetic tenant context so parallel tests are unaffected. */
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

    /** Bind the caller as an active verified patient. */
    private void asPatient(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "CONVERSATION", "corr-new-recipient"));
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("patient"));
    }

    /** Bind the caller as an active staff member with one bounded role. */
    private void asStaff(String subject, String role) {
        TenantContext.set(new TenantContext(ORG, subject, "CONVERSATION", "corr-new-recipient"));
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(role));
    }

    /** Synthetic conversation projection with one recipient unit and one connection. */
    private Conversation conversation(String unitKey, String connectionId, State state, int version) {
        return new Conversation(CONVERSATION_ID, version, VERIFIED_PATIENT_ID, PATIENT_SUBJECT,
                unitKey, connectionId, "topic", state, null, 0, 0, NOW);
    }

    /** Capture the ApiException raised by a refused command. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert that no command wrote a success audit event. */
    private void assertNoSuccessAudit() {
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), eq("SUCCESS"));
    }

    /**
     * A patient may open a conversation only through a verified connection: the
     * patient and connection identities persisted come from the server-side
     * resolver, never from the client request, and the verified unit is bound once
     * so no second recipient is written. The real resolver also checks unit-key
     * equality; this mock characterizes the assignment, not that equality check.
     */
    @Test
    void openingThroughAVerifiedConnectionBindsOneRecipientUnit() {
        asPatient(PATIENT_SUBJECT);
        when(repository.patientExists(CLIENT_PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive(PATIENT_SUBJECT, CLIENT_PATIENT_ID)).thenReturn(true);
        when(connections.requireActive(CLIENT_PATIENT_ID, "clinic", CLIENT_CONNECTION_ID))
                .thenReturn(new VerifiedConnection(VERIFIED_PATIENT_ID, VERIFIED_CONNECTION_ID, "clinic"));
        when(repository.find(any())).thenReturn(Optional.of(conversation("clinic", VERIFIED_CONNECTION_ID,
                State.AWAITING_STAFF, 1)));

        Conversation created = service.open(new OpenRequest(CLIENT_PATIENT_ID, PATIENT_SUBJECT,
                "clinic", CLIENT_CONNECTION_ID, null));

        assertEquals("clinic", created.unitKey());
        verify(connections).requireActive(CLIENT_PATIENT_ID, "clinic", CLIENT_CONNECTION_ID);
        verify(repository, times(1)).insertConversation(any(), eq(VERIFIED_PATIENT_ID), eq(PATIENT_SUBJECT),
                eq("clinic"), eq(VERIFIED_CONNECTION_ID), isNull(), eq("awaiting_staff"));
        verify(repository).appendEvent(any(), eq(1), eq("created"), eq(PATIENT_SUBJECT),
                isNull(), eq("awaiting_staff"), any());
        verify(audit).append(any(), eq("CONVERSATION_OPEN"), eq("CONVERSATION_OPEN"),
                eq("CONVERSATION"), eq("CONVERSATION"), any(), eq("SUCCESS"));
    }

    /**
     * A work item cannot be handed to a staff member who is eligible only for a
     * different care unit: the bounded role and unit matrix refuses the target
     * before any assignment, work fact or success audit is written.
     */
    @Test
    void transferToAStaffMemberEligibleOnlyForAnotherUnitIsRefused() {
        asStaff(STAFF_SUBJECT, "clinician");
        when(repository.loadForUpdate(CONVERSATION_ID))
                .thenReturn(Optional.of(conversation("clinic", VERIFIED_CONNECTION_ID,
                        State.AWAITING_STAFF, 1)));
        when(repository.hasActiveMembership(LAB_STAFF_SUBJECT)).thenReturn(true);
        when(repository.activeRoles(LAB_STAFF_SUBJECT)).thenReturn(List.of("lab_collector"));

        ApiException failure = reject(() -> service.transfer(CONVERSATION_ID,
                new TransferRequest(LAB_STAFF_SUBJECT, "coverage"), 1));

        assertEquals(422, failure.status());
        assertEquals("INVALID_ASSIGNEE", failure.code());
        verify(repository, never()).assign(anyString(), anyInt(), anyString());
        verify(repository, never()).appendEvent(any(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        assertNoSuccessAudit();
    }

    /**
     * A patient cannot present another patient's verified connection to open a
     * conversation for a new recipient: the mismatched principal is concealed as
     * a not-found before any existence probe or write.
     */
    @Test
    void aPatientCannotUseAnotherPatientsConnectionForANewRecipient() {
        asPatient(PATIENT_SUBJECT);

        ApiException failure = reject(() -> service.open(new OpenRequest(CLIENT_PATIENT_ID,
                OTHER_PATIENT_SUBJECT, "clinic", CLIENT_CONNECTION_ID, null)));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).patientExists(anyString());
        verify(repository, never()).patientLinkActive(anyString(), anyString());
        verify(connections, never()).requireActive(any(), any(), any());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
        assertNoSuccessAudit();
    }

    /**
     * A connection verified for one care unit cannot authorize a new recipient
     * unit: the server-side resolver refuses the mismatch, so the second unit
     * needs its own verified connection and its own conversation.
     */
    @Test
    void aConnectionVerifiedForAnotherUnitCannotOpenAConversationForANewRecipient() {
        asPatient(PATIENT_SUBJECT);
        when(repository.patientExists(CLIENT_PATIENT_ID)).thenReturn(true);
        when(repository.patientLinkActive(PATIENT_SUBJECT, CLIENT_PATIENT_ID)).thenReturn(true);
        when(connections.requireActive(CLIENT_PATIENT_ID, "lab", CLIENT_CONNECTION_ID))
                .thenThrow(new ApiException(403, "FORBIDDEN", "the connection is not for this unit"));

        ApiException failure = reject(() -> service.open(new OpenRequest(CLIENT_PATIENT_ID,
                PATIENT_SUBJECT, "lab", CLIENT_CONNECTION_ID, null)));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).insertConversation(any(), any(), any(), any(), any(), any(), any());
        assertNoSuccessAudit();
    }
}
