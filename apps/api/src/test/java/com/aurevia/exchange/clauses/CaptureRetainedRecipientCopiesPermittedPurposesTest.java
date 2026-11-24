package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.response.ExchangeResponseActionPolicy;
import com.aurevia.exchange.response.ExchangeResponseCommands.AcceptRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ChangeResponseRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeOrder;
import com.aurevia.exchange.response.ExchangeResponseCommands.OrderView;
import com.aurevia.exchange.response.ExchangeResponseCommands.Revision;
import com.aurevia.exchange.response.ExchangeResponseCommands.RetainedCopy;
import com.aurevia.exchange.response.ExchangeResponseRepository;
import com.aurevia.exchange.response.ExchangeResponseService;
import com.aurevia.exchange.response.ExchangeState;
import com.aurevia.tenant.TenantContext;

/**
 * XCH-03 clause 3 (API test): an accepted exchange captures an immutable retained
 * recipient copy with its permitted purpose, content checksum and approved
 * retention policy. Every case enters through
 * {@link com.aurevia.exchange.response.ExchangeResponseService#accept},
 * the command behind {@code POST /api/v1/xch03/orders/{id}/accept}, or through
 * {@link com.aurevia.exchange.response.ExchangeResponseService#changeResponse},
 * the command behind a linked change response. The repository's retained-copy
 * ledger is fed by the very {@code insertRetained} write the service performs, so
 * a read back through {@code get} proves the captured values rather than a canned
 * stub. All identifiers, subjects and timestamps are synthetic.
 */
class CaptureRetainedRecipientCopiesPermittedPurposesTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "lab-verifier-a";
    private static final String ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String NEXT_CHECKSUM = "b".repeat(64);
    private static final Instant WHEN = Instant.parse("2026-11-22T10:00:00Z");

    private ExchangeResponseRepository repository;
    private AuditAppender audit;
    private ExchangeResponseService service;
    /** Append-only ledger fed by the real retained-copy writes of the service. */
    private final List<RetainedCopy> retainedLedger = new ArrayList<>();

    /**
     * Build the service with a tenant context, a transaction that runs the real
     * callback and a retained-copy ledger derived from the service's own writes.
     */
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
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-retained-copy"));
        authorized("lab_verifier");
        when(repository.update(anyString(), anyInt(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(1);
        when(repository.find(anyString()))
                .thenReturn(Optional.of(order(ExchangeState.ACCEPTED, 2, 1, CHECKSUM, "RESULT_REVIEW", "STANDARD")));
        when(repository.history(anyString())).thenReturn(List.of());
        when(repository.revisions(anyString())).thenReturn(List.of());
        when(repository.notifications(anyString())).thenReturn(List.of());
        // Return the live mutable ledger on purpose: only OrderView's own
        // defensive copy can then keep a previously read view stable.
        when(repository.retained(anyString())).thenAnswer(invocation -> retainedLedger);
        // Capture the nine arguments of the real insert so the read-back ledger is
        // evidence of the write, not an independent fixture that could disagree.
        doAnswer(invocation -> {
            String id = invocation.getArgument(1);
            int revision = invocation.getArgument(3);
            String checksum = invocation.getArgument(4);
            String purpose = invocation.getArgument(5);
            String policy = invocation.getArgument(6);
            boolean legalHold = invocation.getArgument(7);
            String actor = invocation.getArgument(8);
            retainedLedger.add(new RetainedCopy(id, revision, checksum, purpose, policy, legalHold, actor, WHEN));
            return null;
        }).when(repository).insertRetained(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyBoolean(), anyString());
    }

    /** Remove the synthetic tenant context so other tests are unaffected. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant the synthetic actor active membership and the given role keys. */
    private void authorized(String... roles) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(roles));
    }

    /** Synthetic order projection carrying the captured purpose and retention policy. */
    private static ExchangeOrder order(ExchangeState state, int version, int revision, String checksum,
                                       String purpose, String retention) {
        return new ExchangeOrder(ORDER, version, "LAB-REQ-1", state, revision, checksum,
                PATIENT, "clinician-a", null, null, purpose, retention);
    }

    /** Lock the synthetic order at the given state and version for the command under test. */
    private void locked(ExchangeState state, int version) {
        when(repository.findForUpdate(ORDER))
                .thenReturn(Optional.of(order(state, version, 1, CHECKSUM, null, null)));
    }

    /** Synthetic accept command with a verified association, purpose and policy. */
    private static AcceptRequest acceptRequest(String purpose, String retention) {
        return new AcceptRequest(PATIENT, "clinician-a", purpose, retention);
    }

    /** Synthetic retained-copy row with a fixed timestamp for read-back assertions. */
    private static RetainedCopy copy(int revision, String checksum, String purpose, String policy, boolean hold) {
        return new RetainedCopy("cccccccc-cccc-cccc-cccc-cccccccccccc", revision, checksum,
                purpose, policy, hold, ACTOR, WHEN);
    }

    /** Capture the ApiException raised by a refused command. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert that the refusal wrote no retained row and the read-back ledger is empty. */
    private void assertNoRetainedCopyWasWritten() {
        verify(repository, never()).insertRetained(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyBoolean(), anyString());
        assertTrue(retainedLedger.isEmpty());
        assertTrue(service.get(ORDER).retained().isEmpty());
    }

    /** Acceptance captures purpose, checksum and policy in one immutable retained copy. */
    @Test
    void acceptCapturesPurposePolicyChecksumAndImmutableRetainedCopy() {
        locked(ExchangeState.REQUESTED, 1);

        service.accept(ORDER, 1, acceptRequest("RESULT_REVIEW", "STANDARD"));

        // The update arguments, not the mocked find result, prove the accepted
        // state and the captured purpose, policy and revision were persisted.
        verify(repository).update(eq(ORDER), eq(1), eq(ExchangeState.ACCEPTED), eq(PATIENT),
                eq("clinician-a"), isNull(), isNull(), eq("RESULT_REVIEW"), eq("STANDARD"), eq(1));
        verify(repository).insertRetained(eq(ORG), anyString(), eq(ORDER), eq(1), eq(CHECKSUM),
                eq("RESULT_REVIEW"), eq("STANDARD"), eq(false), eq(ACTOR));

        OrderView view = service.get(ORDER);
        assertEquals(1, view.retained().size());
        RetainedCopy retained = view.retained().get(0);
        assertEquals(1, retained.revision());
        assertEquals(CHECKSUM, retained.contentChecksum());
        assertEquals("RESULT_REVIEW", retained.permittedPurpose());
        assertEquals("STANDARD", retained.retentionPolicy());
        assertFalse(retained.legalHold());
        assertEquals(ACTOR, retained.actorId());
        assertEquals(WHEN, retained.occurredAt());
        assertDoesNotThrow(() -> UUID.fromString(retained.id()));
        // The projection freezes the ledger: neither a caller nor a later write
        // to the source list can mutate a retained copy that was already read.
        assertThrows(UnsupportedOperationException.class, () -> view.retained().clear());
        retainedLedger.add(copy(2, NEXT_CHECKSUM, "RESULT_REVIEW", "STANDARD", false));
        assertEquals(1, view.retained().size());
    }

    /** A legal hold is stamped on the retained copy instead of standard expiry. */
    @Test
    void acceptUnderLegalHoldSetsTheHoldFlag() {
        locked(ExchangeState.REQUESTED, 1);

        service.accept(ORDER, 1, acceptRequest("RESULT_REVIEW", "LEGAL_HOLD"));

        verify(repository).insertRetained(eq(ORG), anyString(), eq(ORDER), eq(1), eq(CHECKSUM),
                eq("RESULT_REVIEW"), eq("LEGAL_HOLD"), eq(true), eq(ACTOR));
        RetainedCopy retained = service.get(ORDER).retained().get(0);
        assertEquals("LEGAL_HOLD", retained.retentionPolicy());
        assertTrue(retained.legalHold());
    }

    /** A promoted change retains its new revision under the purpose captured at acceptance. */
    @Test
    void respondingToAPromotedChangeRetainsTheNewRevisionUnderTheCapturedPurpose() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(
                order(ExchangeState.CHANGE_PENDING, 2, 1, CHECKSUM, "RESULT_REVIEW", "STANDARD")));
        when(repository.find(ORDER)).thenReturn(Optional.of(
                order(ExchangeState.ACCEPTED, 3, 2, NEXT_CHECKSUM, "RESULT_REVIEW", "STANDARD")));
        when(repository.currentRevision(ORDER, 2)).thenReturn(Optional.of(
                new Revision("rev-2", 2, 1, NEXT_CHECKSUM, "corrected result", ACTOR, WHEN)));

        service.changeResponse(ORDER, 2, new ChangeResponseRequest("accept", null));

        verify(repository).update(eq(ORDER), eq(2), eq(ExchangeState.ACCEPTED), eq(PATIENT),
                eq("clinician-a"), isNull(), isNull(), eq("RESULT_REVIEW"), eq("STANDARD"), eq(2));
        verify(repository).insertRetained(eq(ORG), anyString(), eq(ORDER), eq(2), eq(NEXT_CHECKSUM),
                eq("RESULT_REVIEW"), eq("STANDARD"), eq(false), eq(ACTOR));
        OrderView view = service.get(ORDER);
        assertEquals(1, view.retained().size());
        RetainedCopy retained = view.retained().get(0);
        assertEquals(2, retained.revision());
        assertEquals(NEXT_CHECKSUM, retained.contentChecksum());
        assertEquals("RESULT_REVIEW", retained.permittedPurpose());
        assertEquals("STANDARD", retained.retentionPolicy());
        assertFalse(retained.legalHold());
    }

    /** A promoted change keeps the legal hold captured at the original acceptance. */
    @Test
    void changeResponseAppliesTheCapturedLegalHoldToTheNewRetainedCopy() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(
                order(ExchangeState.CHANGE_PENDING, 2, 1, CHECKSUM, "RESULT_REVIEW", "LEGAL_HOLD")));
        when(repository.find(ORDER)).thenReturn(Optional.of(
                order(ExchangeState.ACCEPTED, 3, 2, NEXT_CHECKSUM, "RESULT_REVIEW", "LEGAL_HOLD")));
        when(repository.currentRevision(ORDER, 2)).thenReturn(Optional.of(
                new Revision("rev-2", 2, 1, NEXT_CHECKSUM, "corrected result", ACTOR, WHEN)));

        service.changeResponse(ORDER, 2, new ChangeResponseRequest("accept", null));

        verify(repository).insertRetained(eq(ORG), anyString(), eq(ORDER), eq(2), eq(NEXT_CHECKSUM),
                eq("RESULT_REVIEW"), eq("LEGAL_HOLD"), eq(true), eq(ACTOR));
        RetainedCopy retained = service.get(ORDER).retained().get(0);
        assertEquals("LEGAL_HOLD", retained.retentionPolicy());
        assertTrue(retained.legalHold());
    }

    /** A promoted change with no published revision is refused before any copy is written. */
    @Test
    void changeResponseWithoutAPublishedRevisionWritesNoRetainedCopy() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(
                order(ExchangeState.CHANGE_PENDING, 2, 1, CHECKSUM, "RESULT_REVIEW", "STANDARD")));
        when(repository.currentRevision(ORDER, 2)).thenReturn(Optional.empty());

        ApiException failure = reject(() -> service.changeResponse(ORDER, 2, new ChangeResponseRequest("accept", null)));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        assertNoRetainedCopyWasWritten();
    }

    /** A change with no captured purpose is refused before a NOT NULL copy can be written. */
    @Test
    void changeResponseRefusesToPromoteAChangeWithoutACapturedPurpose() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(
                order(ExchangeState.CHANGE_PENDING, 2, 1, CHECKSUM, null, "STANDARD")));

        ApiException failure = reject(() -> service.changeResponse(ORDER, 2, new ChangeResponseRequest("accept", null)));

        assertEquals(409, failure.status());
        assertEquals("INVALID_TRANSITION", failure.code());
        assertNoRetainedCopyWasWritten();
    }

    /** A change with no captured retention policy is refused before any copy is written. */
    @Test
    void changeResponseRefusesToPromoteAChangeWithoutACapturedRetentionPolicy() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(
                order(ExchangeState.CHANGE_PENDING, 2, 1, CHECKSUM, "RESULT_REVIEW", null)));

        ApiException failure = reject(() -> service.changeResponse(ORDER, 2, new ChangeResponseRequest("accept", null)));

        assertEquals(409, failure.status());
        assertEquals("INVALID_TRANSITION", failure.code());
        assertNoRetainedCopyWasWritten();
    }

    /** Rejecting a linked change leaves the accepted revision current and writes no copy. */
    @Test
    void rejectingALinkedChangeLeavesTheAcceptedRevisionCurrentAndWritesNoCopy() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(
                order(ExchangeState.CHANGE_PENDING, 2, 1, CHECKSUM, "RESULT_REVIEW", "STANDARD")));
        when(repository.find(ORDER)).thenReturn(Optional.of(
                order(ExchangeState.REJECTED, 3, 1, CHECKSUM, "RESULT_REVIEW", "STANDARD")));

        service.changeResponse(ORDER, 2, new ChangeResponseRequest("reject", "duplicate result"));

        // A rejected change writes no new revision: revision one stays current.
        verify(repository).update(eq(ORDER), eq(2), eq(ExchangeState.REJECTED), eq(PATIENT),
                eq("clinician-a"), eq("duplicate result"), isNull(),
                eq("RESULT_REVIEW"), eq("STANDARD"), eq(1));
        assertNoRetainedCopyWasWritten();
    }

    /** A read-only role cannot accept and therefore captures no retained copy. */
    @Test
    void acceptIsRefusedForARoleWithoutExchangePermission() {
        authorized("privacy_auditor");
        locked(ExchangeState.REQUESTED, 1);

        ApiException failure = reject(() -> service.accept(ORDER, 1, acceptRequest("RESULT_REVIEW", "STANDARD")));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        // Membership passed, so the denial is specifically the role check, and
        // the denial is audited before the command returns.
        verify(repository).activeRoles(ACTOR);
        verify(repository, never()).findForUpdate(anyString());
        verify(audit).append(any(), eq("EXCHANGE_ACCEPT"), eq("EXCHANGE_ACCEPT"), eq("EXCHANGE_ORDER"),
                eq("XCH03"), eq(ORDER), eq("DENIED"));
        assertNoRetainedCopyWasWritten();
    }

    /** Stale, forbidden-state and invalid input refusals never retain a copy. */
    @Test
    void acceptIsRefusedOnStaleVersionWrongStateAndInvalidInputWithoutRetaining() {
        locked(ExchangeState.REQUESTED, 1);
        assertEquals(412, reject(() -> service.accept(ORDER, 2,
                acceptRequest("RESULT_REVIEW", "STANDARD"))).status());

        locked(ExchangeState.ACCEPTED, 1);
        assertEquals(409, reject(() -> service.accept(ORDER, 1,
                acceptRequest("RESULT_REVIEW", "STANDARD"))).status());

        locked(ExchangeState.REQUESTED, 1);
        assertEquals(422, reject(() -> service.accept(ORDER, 1,
                acceptRequest(" ", "STANDARD"))).status());
        ApiException policy = reject(() -> service.accept(ORDER, 1,
                acceptRequest("RESULT_REVIEW", "UNKNOWN_POLICY")));
        assertEquals(422, policy.status());
        assertEquals("INVALID_RETENTION_POLICY", policy.code());
        assertNoRetainedCopyWasWritten();
    }
}
