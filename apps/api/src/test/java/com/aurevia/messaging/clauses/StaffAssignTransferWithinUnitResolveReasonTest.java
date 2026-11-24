package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.aurevia.messaging.conversations.ConversationContract.AssignRequest;
import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.ResolveRequest;
import com.aurevia.messaging.conversations.ConversationContract.State;
import com.aurevia.messaging.conversations.ConversationContract.TransferRequest;
import com.aurevia.messaging.conversations.ConversationRepository;
import com.aurevia.messaging.conversations.ConversationService;
import com.aurevia.messaging.conversations.PatientConnectionVerifier;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-02 clause 4 (API test): an authorized unit staff member can assign,
 * transfer inside the unit or resolve a work item with a reason and an audit
 * row, while a wrong role, a target outside the unit, a stale version, a closed
 * thread and a blank reason are all refused without a durable write.
 *
 * <p>Every case enters through the same commands the conversation controller
 * calls ({@code assign}, {@code transfer} and {@code resolve} on the real
 * {@code ConversationService}) and uses synthetic subjects and identifiers.
 * The transaction template runs the real guarded callback synchronously, so a
 * refusal is proven to leave both the conversation projection and the
 * append-only work-item event ledger untouched. The action matrix is real;
 * only the SQL-facing boundaries are mocked.</p>
 */
class StaffAssignTransferWithinUnitResolveReasonTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String CONNECTION_ID = "44444444-4444-4444-4444-444444444444";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final String ACTOR = "lab-a";
    private static final String TARGET = "lab-b";
    private static final String NEXT_TARGET = "lab-c";
    private static final String FOREIGN_TARGET = "clinician-b";

    private ConversationRepository repository;
    private AuditAppender audit;
    private ConversationService service;

    /** Build the service with the real action matrix and a synchronous transaction callback. */
    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
        audit = mock(AuditAppender.class);
        service = new ConversationService(repository, new ConversationActions(), transactions(), audit,
                mock(PatientConnectionVerifier.class));
        bind(ACTOR);
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

    /** Bind a synthetic tenant context for the active subject. */
    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "CONVERSATION", "corr-clause-4"));
    }

    /** Grant a caller an active membership and exactly one role. */
    private void asRole(String subject, String role) {
        bind(subject);
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(role));
    }

    /** Register an active same-unit staff member as an assignment target. */
    private void asEligibleTarget(String subject) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("lab_verifier"));
    }

    /** Synthetic lab conversation at a given version, state and assignee. */
    private static Conversation conversation(State state, int version, String assignee) {
        return new Conversation(ID, version, PATIENT_ID, "patient-a", "lab", CONNECTION_ID,
                "topic", state, assignee, 0, 0, NOW);
    }

    /** Capture the ApiException raised by a refused command. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert that a refusal wrote no assignment, state change, event or successful audit row. */
    private void assertNoWorkItemWasWritten() {
        verify(repository, never()).assign(anyString(), anyInt(), anyString());
        verify(repository, never()).updateState(anyString(), anyInt(), anyString());
        verify(repository, never()).appendEvent(anyString(), anyInt(), anyString(), anyString(),
                any(), any(), any());
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), eq("SUCCESS"));
    }

    /** Assign, transfer and resolve record reasoned work-item events and successful audit rows. */
    @Test
    void assignTransferAndResolveRecordReasonedEventsAndAudit() {
        asRole(ACTOR, "lab_verifier");
        asEligibleTarget(TARGET);
        asEligibleTarget(NEXT_TARGET);
        when(repository.loadForUpdate(ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, 1, null)),
                Optional.of(conversation(State.AWAITING_STAFF, 2, TARGET)),
                Optional.of(conversation(State.AWAITING_STAFF, 3, NEXT_TARGET)));
        when(repository.find(ID)).thenReturn(
                Optional.of(conversation(State.AWAITING_STAFF, 2, TARGET)),
                Optional.of(conversation(State.AWAITING_STAFF, 3, NEXT_TARGET)),
                Optional.of(conversation(State.RESOLVED, 4, NEXT_TARGET)));

        Conversation assigned = service.assign(ID, new AssignRequest(TARGET, " cover "), 1);
        Conversation transferred = service.transfer(ID, new TransferRequest(NEXT_TARGET, " handover "), 2);
        Conversation resolved = service.resolve(ID, new ResolveRequest(" answered "), 3);

        assertEquals(TARGET, assigned.assigneeSubject());
        assertEquals(NEXT_TARGET, transferred.assigneeSubject());
        assertEquals(State.RESOLVED, resolved.state());
        assertEquals(4, resolved.version());
        verify(repository).assign(ID, 1, TARGET);
        verify(repository).assign(ID, 2, NEXT_TARGET);
        verify(repository).updateState(ID, 3, "resolved");
        verify(repository).appendEvent(ID, 2, "assigned", ACTOR, "awaiting_staff", "awaiting_staff", "cover");
        verify(repository).appendEvent(ID, 3, "transferred", ACTOR, "awaiting_staff", "awaiting_staff", "handover");
        verify(repository).appendEvent(ID, 4, "resolved", ACTOR, "awaiting_staff", "resolved", "answered");
        verify(audit).append(any(), eq("WORK_ASSIGN"), eq("WORK_ASSIGN"), eq("CONVERSATION"),
                eq("CONVERSATION"), eq(ID), eq("SUCCESS"));
        verify(audit).append(any(), eq("WORK_TRANSFER"), eq("WORK_TRANSFER"), eq("CONVERSATION"),
                eq("CONVERSATION"), eq(ID), eq("SUCCESS"));
        verify(audit).append(any(), eq("WORK_RESOLVE"), eq("WORK_RESOLVE"), eq("CONVERSATION"),
                eq("CONVERSATION"), eq(ID), eq("SUCCESS"));
    }

    /** A patient role cannot assign a work item and the denial is audited. */
    @Test
    void assignIsRefusedForAPatientRole() {
        asRole("patient-a", "patient");

        ApiException failure = reject(() -> service.assign(ID, new AssignRequest(TARGET, "cover"), 1));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).loadForUpdate(anyString());
        verify(audit).append(any(), eq("WORK_ASSIGN"), eq("WORK_ASSIGN"), eq("CONVERSATION"),
                eq("CONVERSATION"), eq(ID), eq("DENIED"));
        assertNoWorkItemWasWritten();
    }

    /** A target whose role belongs to another unit cannot receive a transfer. */
    @Test
    void transferIsRefusedForATargetInAnotherUnit() {
        asRole(ACTOR, "lab_verifier");
        when(repository.hasActiveMembership(FOREIGN_TARGET)).thenReturn(true);
        when(repository.activeRoles(FOREIGN_TARGET)).thenReturn(List.of("clinician"));
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 1, null)));

        ApiException failure = reject(() -> service.transfer(ID, new TransferRequest(FOREIGN_TARGET, "cover"), 1));

        assertEquals(422, failure.status());
        assertEquals("INVALID_ASSIGNEE", failure.code());
        assertNoWorkItemWasWritten();
    }

    /** A stale version cannot assign because another writer moved the conversation first. */
    @Test
    void assignIsRefusedOnStaleVersion() {
        asRole(ACTOR, "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.AWAITING_STAFF, 2, null)));

        ApiException failure = reject(() -> service.assign(ID, new AssignRequest(TARGET, "cover"), 1));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        assertNoWorkItemWasWritten();
    }

    /** A closed thread is read-only, so it cannot be resolved. */
    @Test
    void resolveIsRefusedOnAClosedThread() {
        asRole(ACTOR, "lab_verifier");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(conversation(State.CLOSED, 1, null)));

        ApiException failure = reject(() -> service.resolve(ID, new ResolveRequest("done"), 1));

        assertEquals(409, failure.status());
        assertEquals("CLOSED_THREAD", failure.code());
        assertNoWorkItemWasWritten();
    }

    /** A blank reason is rejected before any repository or audit call. */
    @Test
    void resolveIsRefusedForBlankReason() {
        ApiException failure = reject(() -> service.resolve(ID, new ResolveRequest(" "), 1));

        assertEquals(422, failure.status());
        assertEquals("INVALID_REASON", failure.code());
        verifyNoInteractions(repository, audit);
    }
}
