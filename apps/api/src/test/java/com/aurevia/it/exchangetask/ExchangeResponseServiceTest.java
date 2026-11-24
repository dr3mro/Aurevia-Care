package com.aurevia.it.exchangetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeEvent;
import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeOrder;
import com.aurevia.exchange.response.ExchangeState;
import com.aurevia.exchange.response.ExchangeResponseCommands.Notification;
import com.aurevia.exchange.response.ExchangeResponseCommands.OrderView;
import com.aurevia.exchange.response.ExchangeResponseCommands.RetainedCopy;
import com.aurevia.exchange.response.ExchangeResponseCommands.Revision;
import com.aurevia.exchange.response.ExchangeResponseActionPolicy;
import com.aurevia.exchange.response.ExchangeResponseCommands.AcceptRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ChangeResponseRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ClarifyRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.CancellationResponseRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ProposeChangeRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ReasonRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.RevokeRequest;
import com.aurevia.exchange.response.ExchangeResponseRepository;
import com.aurevia.exchange.response.ExchangeResponseService;
import com.aurevia.tenant.TenantContext;

/**
 * S-G2-03 unit coverage: every legal and illegal XCH-03 transition, mandatory
 * reasons and questions, version preconditions, authorization, immutable
 * revisions and retained copies, cancellation response, revocation blocking and
 * downstream notification. No Spring context or database is used.
 */
class ExchangeResponseServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "lab-verifier-a";
    private static final String SENDER = "clinician-a";
    private static final String ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String NEXT_CHECKSUM = "b".repeat(64);

    private ExchangeResponseRepository repository;
    private AuditAppender audit;
    private ExchangeResponseService service;

    @BeforeEach
    void setUp() {
        repository = mock(ExchangeResponseRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new ExchangeResponseService(repository, new ExchangeResponseActionPolicy(), transactions, audit);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-1"));
        authorized("lab_verifier");
        when(repository.update(anyString(), anyInt(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(1);
        when(repository.find(anyString())).thenAnswer(invocation ->
                Optional.of(order(ExchangeState.ACCEPTED, 2)));
        when(repository.currentRevision(eq(ORDER), anyInt())).thenReturn(Optional.of(new Revision(
                "rev-2", 2, 1, NEXT_CHECKSUM, "change", ACTOR, Instant.parse("2026-11-19T12:00:00Z"))));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String... roles) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(roles));
    }

    /** Switch the request to a sender-side clinician for sender-originated commands. */
    private void asSender() {
        TenantContext.set(new TenantContext(ORG, SENDER, "EXCHANGE", "corr-2"));
        when(repository.hasActiveMembership(SENDER)).thenReturn(true);
        when(repository.activeRoles(SENDER)).thenReturn(List.of("clinician"));
        when(repository.currentRevision(eq(ORDER), anyInt())).thenReturn(Optional.of(new Revision(
                "rev-2", 2, 1, NEXT_CHECKSUM, "change", SENDER, Instant.parse("2026-11-19T12:00:00Z"))));
    }

    private static ExchangeOrder order(ExchangeState state, int version) {
        return new ExchangeOrder(ORDER, version, "LAB-REQ-1", state, 1, CHECKSUM,
                null, null, null, null, null, null);
    }

    private static ExchangeOrder acceptedWithPurpose() {
        return new ExchangeOrder(ORDER, 2, "LAB-REQ-1", ExchangeState.ACCEPTED, 1, CHECKSUM,
                PATIENT, "clinician-a", null, null, "RESULT_REVIEW", "STANDARD");
    }

    private void locked(ExchangeState state, int version) {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(order(state, version)));
    }

    private void lockedChangePending() {
        // A change is pending on the *accepted* revision: the linked revision lives only
        // in the revision ledger until the recipient responds and the order version is
        // promoted. Retrieval must keep serving this last released revision meanwhile.
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(new ExchangeOrder(ORDER, 2,
                "LAB-REQ-1", ExchangeState.CHANGE_PENDING, 1, CHECKSUM, PATIENT, "clinician-a",
                null, null, "RESULT_REVIEW", "STANDARD")));
    }

    private static AcceptRequest acceptRequest() {
        return new AcceptRequest(PATIENT, "clinician-a", "RESULT_REVIEW", "STANDARD");
    }

    @Test
    void acceptsWithVerifiedAssociationPurposeAndRetainedCopy() {
        locked(ExchangeState.REQUESTED, 1);
        ExchangeOrder result = service.accept(ORDER, 1, acceptRequest());
        assertEquals(ExchangeState.ACCEPTED, result.state());
        verify(repository).insertRetained(eq(ORG), anyString(), eq(ORDER), eq(1), eq(CHECKSUM),
                eq("RESULT_REVIEW"), eq("STANDARD"), eq(false), eq(ACTOR));
        verify(repository).appendEvent(eq(ORG), anyString(), eq(ORDER), eq(2), eq("accept"),
                eq("requested"), eq("accepted"), eq(null), eq(ACTOR));
        verify(audit).append(any(), eq("EXCHANGE_ACCEPT"), eq("EXCHANGE_ACCEPT"), anyString(),
                anyString(), eq(ORDER), eq("SUCCESS"));
    }

    @Test
    void acceptLegalHoldSetsTheRetentionHold() {
        locked(ExchangeState.REQUESTED, 1);
        service.accept(ORDER, 1, new AcceptRequest(PATIENT, null, "RESULT_REVIEW", "LEGAL_HOLD"));
        verify(repository).insertRetained(eq(ORG), anyString(), eq(ORDER), eq(1), eq(CHECKSUM),
                eq("RESULT_REVIEW"), eq("LEGAL_HOLD"), eq(true), eq(ACTOR));
    }

    @Test
    void acceptRejectsBadStateAndInvalidInput() {
        locked(ExchangeState.ACCEPTED, 1);
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.accept(ORDER, 1, acceptRequest())).status());

        locked(ExchangeState.REQUESTED, 1);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.accept(ORDER, 1, new AcceptRequest("not-a-uuid", null, "P", "STANDARD"))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.accept(ORDER, 1, new AcceptRequest(PATIENT, null, " ", "STANDARD"))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.accept(ORDER, 1, new AcceptRequest(PATIENT, null, "P", "UNKNOWN_POLICY"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.accept(ORDER, 1,
                new AcceptRequest(PATIENT, "x".repeat(129), "P", "STANDARD"))).status());
    }

    @Test
    void rejectRequiresADurableReason() {
        locked(ExchangeState.REQUESTED, 1);
        service.reject(ORDER, 1, new ReasonRequest("duplicate order"));
        verify(repository).appendEvent(eq(ORG), anyString(), eq(ORDER), eq(2), eq("reject"),
                eq("requested"), eq("rejected"), eq("duplicate order"), eq(ACTOR));

        locked(ExchangeState.REQUESTED, 1);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.reject(ORDER, 1, new ReasonRequest("  "))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.reject(ORDER, 1, new ReasonRequest("x".repeat(501)))).status());
        assertFalse(capturedReasonIsBlank());
    }

    private boolean capturedReasonIsBlank() {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).appendEvent(eq(ORG), anyString(), eq(ORDER),
                anyInt(), anyString(), anyString(), anyString(), captor.capture(), anyString());
        return captor.getValue().isBlank();
    }

    @Test
    void clarifyRequiresAQuestionAndOnlyRunsWhenRequested() {
        locked(ExchangeState.REQUESTED, 1);
        service.clarify(ORDER, 1, new ClarifyRequest(" Which specimen? "));
        verify(repository).appendEvent(eq(ORG), anyString(), eq(ORDER), eq(2), eq("clarify"),
                eq("requested"), eq("clarification_requested"), eq("Which specimen?"), eq(ACTOR));

        locked(ExchangeState.REQUESTED, 1);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.clarify(ORDER, 1, new ClarifyRequest(" "))).status());
        locked(ExchangeState.ACCEPTED, 1);
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.clarify(ORDER, 1, new ClarifyRequest("why"))).status());
    }

    @Test
    void proposeChangeAppendsALinkedImmutableRevision() {
        asSender();
        locked(ExchangeState.ACCEPTED, 2);
        service.proposeChange(ORDER, 2, new ProposeChangeRequest(" corrected unit ", NEXT_CHECKSUM));
        verify(repository).insertRevision(eq(ORG), anyString(), eq(ORDER), eq(2), eq(1),
                eq(NEXT_CHECKSUM), eq("corrected unit"), eq(SENDER));
        verify(repository).appendEvent(eq(ORG), anyString(), eq(ORDER), eq(3), eq("propose_change"),
                eq("accepted"), eq("change_pending"), eq(null), eq(SENDER));

        // A proposed change must not advance the released revision while it is
        // awaiting the recipient's response (ARC-03, XCH-03).
        verify(repository).update(eq(ORDER), eq(2), eq(ExchangeState.CHANGE_PENDING), eq(null),
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(1));
    }

    @Test
    void aLinkedChangeRequiresAPriorAcceptance() {
        asSender();
        // XCH-03 scopes changes to *after* acceptance. A clarification-only order
        // has no verified patient, permitted purpose or retained copy, so it cannot
        // enter the change flow (which would otherwise write a NOT NULL violation).
        locked(ExchangeState.CLARIFICATION_REQUESTED, 2);
        assertEquals(409, assertThrows(ApiException.class, () -> service.proposeChange(ORDER, 2,
                new ProposeChangeRequest("clarified", NEXT_CHECKSUM))).status());
        verify(repository, never()).insertRevision(anyString(), anyString(), anyString(),
                anyInt(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void proposeChangeRejectsBadInputsAndWrongStates() {
        asSender();
        locked(ExchangeState.REQUESTED, 1);
        assertEquals(409, assertThrows(ApiException.class, () -> service.proposeChange(ORDER, 1,
                new ProposeChangeRequest("change", NEXT_CHECKSUM))).status());
        locked(ExchangeState.ACCEPTED, 2);
        assertEquals(422, assertThrows(ApiException.class, () -> service.proposeChange(ORDER, 2,
                new ProposeChangeRequest(" ", NEXT_CHECKSUM))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.proposeChange(ORDER, 2,
                new ProposeChangeRequest("change", "not-a-checksum"))).status());
    }

    @Test
    void changeResponseRequiresRecipientDecision() {
        lockedChangePending();
        service.changeResponse(ORDER, 2, new ChangeResponseRequest("accept", null));
        verify(repository).insertRetained(eq(ORG), anyString(), eq(ORDER), eq(2), eq(NEXT_CHECKSUM),
                eq("RESULT_REVIEW"), eq("STANDARD"), eq(false), eq(ACTOR));
        verify(repository).appendEvent(eq(ORG), anyString(), eq(ORDER), eq(3), eq("change_accept"),
                eq("change_pending"), eq("accepted"), eq(null), eq(ACTOR));

        lockedChangePending();
        service.changeResponse(ORDER, 2, new ChangeResponseRequest("reject", "wrong unit"));
        verify(repository).appendEvent(eq(ORG), anyString(), eq(ORDER), eq(3), eq("change_reject"),
                eq("change_pending"), eq("rejected"), eq("wrong unit"), eq(ACTOR));

        lockedChangePending();
        assertEquals(422, assertThrows(ApiException.class, () -> service.changeResponse(ORDER, 2,
                new ChangeResponseRequest("reject", " "))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.changeResponse(ORDER, 2,
                new ChangeResponseRequest("maybe", null))).status());
        locked(ExchangeState.ACCEPTED, 2);
        assertEquals(409, assertThrows(ApiException.class, () -> service.changeResponse(ORDER, 2,
                new ChangeResponseRequest("accept", null))).status());
    }

    @Test
    void acceptingAChangePromotesTheLinkedRevisionOnlyOnRecipientResponse() {
        lockedChangePending();
        service.changeResponse(ORDER, 2, new ChangeResponseRequest("accept", null));
        // The linked revision becomes current only after the recipient accepts it.
        verify(repository).update(eq(ORDER), eq(2), eq(ExchangeState.ACCEPTED), eq(PATIENT),
                eq("clinician-a"), eq(null), eq(null), eq("RESULT_REVIEW"), eq("STANDARD"), eq(2));
        verify(repository).insertRetained(eq(ORG), anyString(), eq(ORDER), eq(2), eq(NEXT_CHECKSUM),
                eq("RESULT_REVIEW"), eq("STANDARD"), eq(false), eq(ACTOR));
    }

    @Test
    void rejectingAChangeKeepsTheAcceptedRevisionRetrievable() {
        lockedChangePending();
        service.changeResponse(ORDER, 2, new ChangeResponseRequest("reject", "wrong unit"));
        // The rejected revision stays only in the ledger; retrieval still serves the
        // last accepted, retained revision (XCH-03, ARC-03).
        verify(repository).update(eq(ORDER), eq(2), eq(ExchangeState.REJECTED), eq(PATIENT),
                eq("clinician-a"), eq("wrong unit"), eq(null), eq("RESULT_REVIEW"),
                eq("STANDARD"), eq(1));
        verify(repository, never()).insertRetained(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void acceptingAChangeWithoutARetainedPurposeFailsClosed() {
        // Defensive: a shell-injected accepted projection with no permitted purpose
        // must never create a retained copy with null NOT NULL columns.
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(new ExchangeOrder(ORDER, 2,
                "LAB-REQ-1", ExchangeState.CHANGE_PENDING, 1, CHECKSUM, null, null,
                null, "Which specimen?", null, null)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.changeResponse(ORDER, 2,
                new ChangeResponseRequest("accept", null))).status());
        verify(repository, never()).insertRetained(anyString(), anyString(), anyString(), anyInt(),
                anyString(), any(), any(), anyBoolean(), anyString());
    }

    @Test
    void acceptingAChangeWithoutARetentionPolicyFailsClosed() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(new ExchangeOrder(ORDER, 2,
                "LAB-REQ-1", ExchangeState.CHANGE_PENDING, 1, CHECKSUM, PATIENT, "clinician-a",
                null, null, "RESULT_REVIEW", null)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.changeResponse(ORDER, 2,
                new ChangeResponseRequest("accept", null))).status());
        verify(repository, never()).insertRetained(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void cancellationIsARequestThatNeedsRecipientResponse() {
        asSender();
        locked(ExchangeState.ACCEPTED, 2);
        service.requestCancellation(ORDER, 2, new ReasonRequest("order entered in error"));
        verify(repository).appendEvent(eq(ORG), anyString(), eq(ORDER), eq(3), eq("request_cancellation"),
                eq("accepted"), eq("cancellation_pending"), eq("order entered in error"), eq(SENDER));

        locked(ExchangeState.REQUESTED, 1);
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.requestCancellation(ORDER, 1, new ReasonRequest("why"))).status());
        locked(ExchangeState.ACCEPTED, 2);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.requestCancellation(ORDER, 2, new ReasonRequest(" "))).status());
    }

    @Test
    void cancellationResponseConfirmsOrDeclinesWithReason() {
        locked(ExchangeState.CANCELLATION_PENDING, 2);
        service.cancellationResponse(ORDER, 2, new CancellationResponseRequest("confirm", null));
        verify(repository).appendEvent(eq(ORG), anyString(), eq(ORDER), eq(3), eq("cancellation_confirm"),
                eq("cancellation_pending"), eq("cancelled"), eq(null), eq(ACTOR));

        locked(ExchangeState.CANCELLATION_PENDING, 2);
        service.cancellationResponse(ORDER, 2, new CancellationResponseRequest("decline", "work in progress"));
        verify(repository).appendEvent(eq(ORG), anyString(), eq(ORDER), eq(3), eq("cancellation_decline"),
                eq("cancellation_pending"), eq("accepted"), eq("work in progress"), eq(ACTOR));

        locked(ExchangeState.CANCELLATION_PENDING, 2);
        assertEquals(422, assertThrows(ApiException.class, () -> service.cancellationResponse(ORDER, 2,
                new CancellationResponseRequest("decline", " "))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.cancellationResponse(ORDER, 2,
                new CancellationResponseRequest("maybe", null))).status());
        locked(ExchangeState.ACCEPTED, 2);
        assertEquals(409, assertThrows(ApiException.class, () -> service.cancellationResponse(ORDER, 2,
                new CancellationResponseRequest("confirm", null))).status());
    }

    @Test
    void revocationRecordsDownstreamNotificationAndBlocksRetrieval() {
        asSender();
        locked(ExchangeState.ACCEPTED, 2);
        RevokeRequest revoke = new RevokeRequest("disclosure revoked", "LEGAL_HOLD",
                "partner-lab", "secure-email", "event-42");
        service.revoke(ORDER, 2, revoke);
        verify(repository).insertNotification(eq(ORG), anyString(), eq(ORDER), eq("event-42"),
                eq("partner-lab"), eq("secure-email"), eq("NOTIFIED"), eq(SENDER));
        verify(repository).appendEvent(eq(ORG), anyString(), eq(ORDER), eq(3), eq("revoke"),
                eq("accepted"), eq("revoked"), eq("disclosure revoked"), eq(SENDER));

        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(order(ExchangeState.REVOKED, 3)));
        assertEquals(410, assertThrows(ApiException.class, () -> service.retrieve(ORDER)).status());
    }

    @Test
    void revocationValidatesReasonAndNotificationInputs() {
        asSender();
        locked(ExchangeState.REQUESTED, 1);
        RevokeRequest valid = new RevokeRequest("reason", "STANDARD", "partner", "channel", "evt-1");
        service.revoke(ORDER, 1, valid);
        verify(repository).insertNotification(eq(ORG), anyString(), eq(ORDER), eq("evt-1"),
                eq("partner"), eq("channel"), eq("NOTIFIED"), eq(SENDER));

        locked(ExchangeState.REVOKED, 3);
        assertEquals(409, assertThrows(ApiException.class, () -> service.revoke(ORDER, 3, valid)).status());
        locked(ExchangeState.REQUESTED, 1);
        assertEquals(422, assertThrows(ApiException.class, () -> service.revoke(ORDER, 1,
                new RevokeRequest(" ", "STANDARD", "partner", "channel", "evt-2"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.revoke(ORDER, 1,
                new RevokeRequest("reason", "BAD_POLICY", "partner", "channel", "evt-2"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.revoke(ORDER, 1,
                new RevokeRequest("reason", "STANDARD", " ", "channel", "evt-2"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.revoke(ORDER, 1,
                new RevokeRequest("reason", "STANDARD", "partner", " ", "evt-2"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.revoke(ORDER, 1,
                new RevokeRequest("reason", "STANDARD", "partner", "channel", " "))).status());
    }

    @Test
    void retrievesOnlyAnActiveReleasedRevision() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(acceptedWithPurpose()));
        Revision revision = new Revision("rev-1", 1, null, CHECKSUM, null, ACTOR,
                Instant.parse("2026-11-19T12:00:00Z"));
        when(repository.currentRevision(ORDER, 1)).thenReturn(Optional.of(revision));
        assertEquals(CHECKSUM, service.retrieve(ORDER).contentChecksum());
        when(repository.currentRevision(ORDER, 1)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.retrieve(ORDER)).status());
    }

    @Test
    void readsConcealMissingOrdersAndValidateIdentifiers() {
        when(repository.find(ORDER)).thenReturn(Optional.of(acceptedWithPurpose()));
        when(repository.history(ORDER)).thenReturn(List.of());
        when(repository.revisions(ORDER)).thenReturn(List.of());
        when(repository.retained(ORDER)).thenReturn(List.of());
        when(repository.notifications(ORDER)).thenReturn(List.of());
        OrderView view = service.get(ORDER);
        assertEquals("LAB-REQ-1", view.order().orderCode());
        assertTrue(view.history().isEmpty());
        assertNotNull(view.revisions());
        assertTrue(view.retained().isEmpty());
        assertTrue(view.notifications().isEmpty());

        when(repository.find(ORDER)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(ORDER)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.get("not-a-uuid")).status());
    }

    @Test
    void deniesEveryCommandWithoutMembershipOrWithTheWrongRole() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.accept(ORDER, 1, acceptRequest())).status());

        authorized("nurse");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.accept(ORDER, 1, acceptRequest())).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.reject(ORDER, 1, new ReasonRequest("no"))).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.proposeChange(ORDER, 1, new ProposeChangeRequest("c", NEXT_CHECKSUM))).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.revoke(ORDER, 1,
                new RevokeRequest("r", "STANDARD", "p", "c", "e"))).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.retrieve(ORDER)).status());
        verify(audit, org.mockito.Mockito.atLeastOnce()).append(any(), anyString(), anyString(),
                anyString(), anyString(), eq(ORDER), eq("DENIED"));
        verify(repository, never()).findForUpdate(anyString());
    }

    @Test
    void acceptanceStaleVersionAndZeroRowUpdateAreRejected() {
        locked(ExchangeState.REQUESTED, 1);
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.accept(ORDER, 7, acceptRequest())).status());
        locked(ExchangeState.REQUESTED, 1);
        when(repository.update(anyString(), anyInt(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(0);
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.accept(ORDER, 1, acceptRequest())).status());
    }

    @Test
    void missingOrderIsConcealedForCommandsAndReads() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.reject(ORDER, 1, new ReasonRequest("why"))).status());
        when(repository.find(ORDER)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(ORDER)).status());
    }

    @Test
    void changeResponseConcealsAMissingReleasedRevision() {
        lockedChangePending();
        when(repository.currentRevision(eq(ORDER), eq(2))).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () ->
                service.changeResponse(ORDER, 2, new ChangeResponseRequest("accept", null))).status());
    }

    @Test
    void changeResponseAppliesTheExistingLegalHoldToTheNewCopy() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(new ExchangeOrder(ORDER, 2,
                "LAB-REQ-1", ExchangeState.CHANGE_PENDING, 1, CHECKSUM, PATIENT, "clinician-a",
                null, null, "RESULT_REVIEW", "LEGAL_HOLD")));
        service.changeResponse(ORDER, 2, new ChangeResponseRequest("accept", null));
        verify(repository).insertRetained(eq(ORG), anyString(), eq(ORDER), eq(2), eq(NEXT_CHECKSUM),
                eq("RESULT_REVIEW"), eq("LEGAL_HOLD"), eq(true), eq(ACTOR));
    }

    @Test
    void retrieveConcealsAMissingOrderAndGetDeniesWithoutMembership() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.retrieve(ORDER)).status());

        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(ORDER)).status());
    }

    @Test
    void historyExposesTheAppendOnlyEventList() {
        ExchangeEvent event = new ExchangeEvent("event-1", 2, "accept", "requested", "accepted",
                null, ACTOR, Instant.parse("2026-11-19T12:00:00Z"));
        when(repository.find(ORDER)).thenReturn(Optional.of(acceptedWithPurpose()));
        when(repository.history(ORDER)).thenReturn(List.of(event));
        when(repository.revisions(ORDER)).thenReturn(List.of());
        when(repository.retained(ORDER)).thenReturn(List.of());
        when(repository.notifications(ORDER)).thenReturn(List.of());
        assertEquals(1, service.get(ORDER).history().size());
        assertEquals("accept", service.history(ORDER).get(0).action());
    }
}
