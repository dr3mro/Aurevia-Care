package com.aurevia.it.exchangeresult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import com.aurevia.exchange.result.ResultActionPolicy;
import com.aurevia.exchange.result.ResultCommands.ImportResultRequest;
import com.aurevia.exchange.result.ResultCommands.RegisterOrderRequest;
import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.ImportOutcome;
import com.aurevia.exchange.result.ResultModel.RecipientNotification;
import com.aurevia.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.exchange.result.ResultModel.ReconciliationReason;
import com.aurevia.exchange.result.ResultModel.ReconciliationStatus;
import com.aurevia.exchange.result.ResultModel.ResultDetail;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultModel.ResultKind;
import com.aurevia.exchange.result.ResultModel.ResultReport;
import com.aurevia.exchange.result.ResultModel.Review;
import com.aurevia.exchange.result.ResultModel.ReviewState;
import com.aurevia.exchange.result.ResultRepository;
import com.aurevia.exchange.result.ResultRoutingService;
import com.aurevia.tenant.TenantContext;

/** S-G2-04 (XCH-04) service branches: routing, matching, reconciliation, corrections and authorization. */
class ResultRoutingServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "lab-verifier-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ORDER = "order-1";
    private static final String SOURCE = "src-lab";
    private static final String ACCESSION = "ACC-1";
    private static final String DEST = "clinic-a";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final String REPORT_1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
    private static final String REPORT_2 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
    private static final String REPORT_X = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa9";
    private static final String NOTE_1 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1";
    private static final String ENTRY_1 = "cccccccc-cccc-cccc-cccc-ccccccccccc1";

    private ResultRepository repository;
    private AuditAppender audit;
    private ResultRoutingService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResultRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new ResultRoutingService(repository, new ResultActionPolicy(), transactions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.insertReport(any())).thenReturn(true);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "XCH04_RESULT_ROUTING", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String... roles) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(roles));
    }

    private static ResultIdentifiers ids() {
        return new ResultIdentifiers(PATIENT, SOURCE, ACCESSION, DEST, ORDER);
    }

    private static ExpectedOrder order() {
        return new ExpectedOrder(ORDER, ids(), "owner-a", "queue-a");
    }

    private static ResultReport result(int version, ResultKind kind, Integer supersedes, String previousId) {
        return new ResultReport(version == 1 ? REPORT_1 : REPORT_2, version, ORDER, ids(), kind,
                supersedes, previousId,
                "owner-a", "queue-a", NOW, NOW, "checksum-1");
    }

    private static ImportResultRequest request(String kind, Integer supersedes) {
        return new ImportResultRequest(ORDER, PATIENT, SOURCE, ACCESSION, DEST, kind, supersedes,
                NOW.toString(), "checksum-1");
    }

    private static RegisterOrderRequest registerRequest() {
        return new RegisterOrderRequest(ORDER, PATIENT, SOURCE, ACCESSION, DEST, "owner-a", "queue-a");
    }

    private void matched() {
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of());
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.empty());
        when(repository.findReview(anyString())).thenReturn(Optional.empty());
        when(repository.priorRecipients(ORDER)).thenReturn(List.of());
    }

    private void audited(String action) {
        verify(audit, atLeastOnce()).append(any(TenantContext.class), eq("XCH04_RESULT_ROUTING"),
                eq(action), anyString(), eq("EXCHANGE"), anyString(), anyString());
    }

    @Test
    void registersAnExpectedOrderAndAudits() {
        authorized("tenant_admin");
        when(repository.findOrder(ORDER)).thenReturn(Optional.empty());
        when(repository.insertOrder(order())).thenReturn(order());
        ExpectedOrder created = service.registerOrder(registerRequest());
        assertEquals(order(), created);
        verify(repository).insertOrder(order());
        audited("XCH04_ORDER_REGISTER");
    }

    @Test
    void refusesToOverwriteAnExistingExpectedOrder() {
        authorized("tenant_admin");
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        ApiException failure = assertThrows(ApiException.class, () -> service.registerOrder(registerRequest()));
        assertEquals(409, failure.status());
        assertEquals("ORDER_EXISTS", failure.code());
        verify(repository, never()).insertOrder(any());
        audited("XCH04_ORDER_REGISTER");
    }

    @Test
    void aConcurrentOrderRegistrationConflictIsReportedAs409() {
        authorized("tenant_admin");
        when(repository.findOrder(ORDER)).thenReturn(Optional.empty());
        when(repository.insertOrder(any())).thenReturn(null);

        ApiException failure = assertThrows(ApiException.class, () -> service.registerOrder(registerRequest()));
        assertEquals(409, failure.status());
        assertEquals("ORDER_EXISTS", failure.code());
        audited("XCH04_ORDER_REGISTER");
    }

    @Test
    void registrationDeniesInactiveMembershipAndWrongRole() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.registerOrder(registerRequest())).status());
        audited("XCH04_ORDER_REGISTER");

        authorized("clinician");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.registerOrder(registerRequest())).status());
    }

    @Test
    void routesAMatchedFinalResultToOwnerAndQueue() {
        authorized("lab_verifier");
        matched();
        ImportOutcome outcome = service.importResult(request("final", null));
        assertNull(outcome.reconciliation());
        ResultReport report = outcome.report();
        assertNotNull(report);
        assertEquals(1, report.version());
        assertEquals("owner-a", report.owner());
        assertEquals("queue-a", report.coverageQueue());
        assertEquals(ResultKind.FINAL, report.kind());
        assertEquals(NOW, report.effectiveAt());
        verify(repository).insertReport(report);
        verify(repository).insertReview(report.id());
        audited("XCH04_RESULT_IMPORT");
    }

    @Test
    void routesACriticalResultWithAnExplicitCriticalAudit() {
        authorized("lab_verifier");
        matched();
        ImportOutcome outcome = service.importResult(request("critical", null));
        assertEquals(ResultKind.CRITICAL, outcome.report().kind());
        audited("XCH04_CRITICAL_ROUTE");
        verify(repository, never()).insertNotification(any());
    }

    @Test
    void divertsAnUnmatchedResultWithoutImportingIt() {
        authorized("lab_verifier");
        when(repository.findOrder(ORDER)).thenReturn(Optional.empty());
        ImportOutcome outcome = service.importResult(request("final", null));
        assertNull(outcome.report());
        ReconciliationEntry entry = outcome.reconciliation();
        assertEquals(ReconciliationReason.UNMATCHED, entry.reason());
        assertEquals(ReconciliationStatus.OPEN, entry.status());
        assertTrue(entry.detail().contains("no expected order"));
        assertEquals(List.of(), entry.mismatches());
        verify(repository, never()).insertReport(any());
        verify(repository, never()).insertReview(anyString());
        audited("XCH04_RESULT_RECONCILED");
    }

    @Test
    void recordsEveryDisagreeingIdentifierForAConflict() {
        authorized("lab_verifier");
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(new ExpectedOrder(ORDER,
                new ResultIdentifiers(PATIENT, "different-source", ACCESSION, DEST, "other-order"),
                "owner-a", "queue-a")));
        ImportOutcome outcome = service.importResult(request("final", null));
        assertEquals(ReconciliationReason.CONFLICT, outcome.reconciliation().reason());
        assertEquals(List.of("sourceId", "orderId"), outcome.reconciliation().mismatches());
        assertTrue(outcome.reconciliation().detail().contains("sourceId"));
        verify(repository, never()).insertReport(any());
    }

    @Test
    void queuesACorrectionWhoseSupersededVersionIsMissing() {
        authorized("lab_verifier");
        matched();
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.empty());
        ImportOutcome outcome = service.importResult(request("corrected", 1));
        assertNull(outcome.report());
        assertEquals(ReconciliationReason.CONFLICT, outcome.reconciliation().reason());
        assertEquals(List.of("supersedesVersion"), outcome.reconciliation().mismatches());
        verify(repository, never()).insertReport(any());
    }

    @Test
    void correctionAppendsANewRevisionReopensReviewAndNotifiesPriorRecipients() {
        authorized("lab_verifier");
        matched();
        ResultReport prior = result(1, ResultKind.FINAL, null, null);
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(prior));
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(prior));
        when(repository.priorRecipients(ORDER)).thenReturn(List.of("owner-a", "previous-owner"));
        when(repository.findReview(REPORT_1)).thenReturn(
                Optional.of(new Review(REPORT_1, 1, ReviewState.ACKNOWLEDGED, NOW)));

        ImportOutcome outcome = service.importResult(request("corrected", 1));
        ResultReport correction = outcome.report();
        assertNotNull(correction);
        assertEquals(2, correction.version());
        assertEquals(ResultKind.CORRECTED, correction.kind());
        assertEquals(1, correction.supersedesVersion());
        assertEquals(REPORT_1, correction.previousId());
        verify(repository).insertReport(correction);
        verify(repository).insertReview(correction.id());
        verify(repository, org.mockito.Mockito.times(2)).insertNotification(any(RecipientNotification.class));
        audited("XCH04_REVIEW_REOPENED");
        audited("XCH04_RESULT_IMPORT");
    }

    @Test
    void correctionOfAnUnreviewedRevisionDoesNotEmitAReopenAudit() {
        authorized("lab_verifier");
        matched();
        ResultReport prior = result(1, ResultKind.FINAL, null, null);
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(prior));
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(prior));
        when(repository.findReview(REPORT_1)).thenReturn(
                Optional.of(new Review(REPORT_1, 1, ReviewState.UNREVIEWED, null)));
        service.importResult(request("corrected", 1));
        verify(audit, never()).append(any(), anyString(), eq("XCH04_REVIEW_REOPENED"),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void replayOfTheSameReportDoesNotDuplicateItOrItsNotifications() {
        authorized("lab_verifier");
        matched();
        ResultReport existing = result(1, ResultKind.FINAL, null, null);
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(existing));
        ImportOutcome outcome = service.importResult(request("final", null));
        assertEquals(existing, outcome.report());
        verify(repository, never()).insertReport(any());
        verify(repository, never()).insertReview(anyString());
        verify(repository, never()).insertNotification(any());
        audited("XCH04_RESULT_REPLAY");
    }

    @Test
    void aReplayAgainstAPopulatedLineageReturnsTheStoredFinal() {
        // The empty-lineage stub in matched() hides the real replay path: after a
        // successful import the repository returns the stored revision from
        // lineage(), so a duplicate final must resolve to that stored revision.
        authorized("lab_verifier");
        ResultReport existing = result(1, ResultKind.FINAL, null, null);
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(existing));
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(existing));
        when(repository.findReview(anyString())).thenReturn(Optional.empty());
        when(repository.priorRecipients(ORDER)).thenReturn(List.of());

        ImportOutcome outcome = service.importResult(request("final", null));

        assertEquals(existing, outcome.report(), "a replay must return the stored final revision");
        assertNull(outcome.reconciliation());
        verify(repository, never()).insertReport(any());
        verify(repository, never()).insertReview(anyString());
        audited("XCH04_RESULT_REPLAY");
    }

    @Test
    void aReplayOfAnOlderFinalAfterACorrectionDoesNotDuplicateOrOverwrite() {
        authorized("lab_verifier");
        ResultReport first = result(1, ResultKind.FINAL, null, null);
        ResultReport correction = result(2, ResultKind.CORRECTED, 1, REPORT_1);
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(first, correction));

        ImportOutcome outcome = service.importResult(request("final", null));

        assertEquals(first, outcome.report(), "an out-of-order final resolves to its stored revision");
        verify(repository, never()).insertReport(any());
        audited("XCH04_RESULT_REPLAY");
    }

    @Test
    void aReplayWithSubMicrosecondEffectiveAtResolvesToTheStoredRevision() {
        // PostgreSQL timestamptz stores microseconds. A caller sending nanoseconds
        // must be normalized at the trust boundary or the fingerprint misses and
        // the replay is inserted as a duplicate version.
        authorized("lab_verifier");
        Instant nanos = Instant.parse("2026-11-19T12:00:00.123456789Z");
        Instant micros = nanos.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        ResultReport existing = new ResultReport(REPORT_1, 1, ORDER, ids(), ResultKind.FINAL,
                null, null, "owner-a", "queue-a", micros, micros, "checksum-1");
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(existing));

        ImportOutcome outcome = service.importResult(new ImportResultRequest(ORDER, PATIENT, SOURCE,
                ACCESSION, DEST, "final", null, nanos.toString(), "checksum-1"));

        assertEquals(existing, outcome.report());
        verify(repository, never()).insertReport(any());
        audited("XCH04_RESULT_REPLAY");
    }

    @Test
    void aNewFindingAfterAnExistingFinalAdvancesTheLineage() {
        authorized("lab_verifier");
        ResultReport first = result(1, ResultKind.FINAL, null, null);
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(first));
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 2)).thenReturn(Optional.empty());

        ImportOutcome outcome = service.importResult(request("critical", null));

        assertNotNull(outcome.report());
        assertEquals(2, outcome.report().version(), "a distinct finding advances the tip");
        assertEquals(ResultKind.CRITICAL, outcome.report().kind());
        verify(repository).insertReport(any());
        audited("XCH04_CRITICAL_ROUTE");
    }

    @Test
    void aConcurrentInsertConflictReturnsTheWinningRevisionWithoutDuplicating() {
        authorized("lab_verifier");
        matched();
        ResultReport winner = result(1, ResultKind.FINAL, null, null);
        // The pre-insert replay check sees no revision; a racing transaction wins
        // the unique key, so the post-insert lookup must return that winning row.
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.insertReport(any())).thenReturn(false);

        ImportOutcome outcome = service.importResult(request("final", null));

        assertEquals(winner, outcome.report());
        assertNull(outcome.reconciliation());
        verify(repository).insertReport(any());
        verify(repository, never()).insertReview(anyString());
        verify(repository, never()).insertNotification(any());
        audited("XCH04_RESULT_REPLAY");
    }

    @Test
    void aReplayWithDifferentContentIsRejectedInsteadOfSilentlyDiscarded() {
        authorized("lab_verifier");
        matched();
        ResultReport stored = result(1, ResultKind.FINAL, null, null);
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(stored));

        ApiException differentChecksum = assertThrows(ApiException.class, () -> service.importResult(
                new ImportResultRequest(ORDER, PATIENT, SOURCE, ACCESSION, DEST, "final", null,
                        NOW.toString(), "different-payload")));
        assertEquals(409, differentChecksum.status());
        assertEquals("PAYLOAD_CONFLICT", differentChecksum.code());

        ApiException differentTime = assertThrows(ApiException.class, () -> service.importResult(
                new ImportResultRequest(ORDER, PATIENT, SOURCE, ACCESSION, DEST, "final", null,
                        NOW.plusSeconds(1).toString(), "checksum-1")));
        assertEquals("PAYLOAD_CONFLICT", differentTime.code());
        audited("XCH04_RESULT_CONFLICT");
        verify(repository, never()).insertReport(any());
    }

    @Test
    void aConcurrentWinnerWithDifferentContentIsRejectedInsteadOfReturned() {
        authorized("lab_verifier");
        matched();
        ResultReport winner = result(1, ResultKind.CRITICAL, null, null);
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.insertReport(any())).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class, () -> service.importResult(request("final", null)));
        assertEquals(409, failure.status());
        assertEquals("PAYLOAD_CONFLICT", failure.code());
        audited("XCH04_RESULT_CONFLICT");
        verify(repository, never()).insertReview(anyString());
        verify(repository, never()).insertNotification(any());
    }

    @Test
    void aCorrectionOfAMissingTipVersionIsQueuedForReconciliation() {
        authorized("lab_verifier");
        ResultReport tip = result(1, ResultKind.FINAL, null, null);
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(tip));
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.empty());

        ImportOutcome outcome = service.importResult(request("corrected", 1));

        assertNull(outcome.report());
        assertEquals(ReconciliationReason.CONFLICT, outcome.reconciliation().reason());
        assertTrue(outcome.reconciliation().detail().contains("not found"));
        verify(repository, never()).insertReport(any());
    }

    @Test
    void aReplayWithDifferentPatientIsRejectedInsteadOfReturned() {
        authorized("lab_verifier");
        matched();
        ResultIdentifiers otherPatient = new ResultIdentifiers(
                "99999999-9999-9999-9999-999999999999", SOURCE, ACCESSION, DEST, ORDER);
        ResultReport foreign = new ResultReport(REPORT_1, 1, ORDER, otherPatient, ResultKind.FINAL,
                null, null, "owner-a", "queue-a", NOW, NOW, "checksum-1");
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(foreign));

        ApiException failure = assertThrows(ApiException.class, () -> service.importResult(request("final", null)));

        assertEquals(409, failure.status());
        assertEquals("PAYLOAD_CONFLICT", failure.code());
        verify(repository, never()).insertReport(any());
    }

    @Test
    void aCorrectionThatSkipsTheCurrentLineageTipIsQueuedForReconciliation() {
        authorized("lab_verifier");
        ResultReport first = result(1, ResultKind.FINAL, null, null);
        ResultReport second = result(2, ResultKind.CORRECTED, 1, REPORT_1);
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(first, second));
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(first));

        ImportOutcome outcome = service.importResult(request("corrected", 1));

        assertNull(outcome.report());
        assertEquals(ReconciliationReason.CONFLICT, outcome.reconciliation().reason());
        assertEquals(List.of("supersedesVersion"), outcome.reconciliation().mismatches());
        verify(repository, never()).insertReport(any());
    }

    @Test
    void aConcurrentConflictWithNoVisibleWinnerFailsClosedWithAConflict() {
        authorized("lab_verifier");
        matched();
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.empty());
        when(repository.insertReport(any())).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class, () -> service.importResult(request("final", null)));
        assertEquals(409, failure.status());
        assertEquals("REPLAY_UNRESOLVED", failure.code());
        verify(repository, never()).insertReview(anyString());
    }

    @Test
    void rejectsSupersedesOnANonCorrectionAndRequiresItOnACorrection() {
        authorized("lab_verifier");
        matched();
        ApiException notAllowed = assertThrows(ApiException.class,
                () -> service.importResult(request("final", 1)));
        assertEquals(422, notAllowed.status());
        assertEquals("SUPERSEDES_NOT_ALLOWED", notAllowed.code());

        ApiException required = assertThrows(ApiException.class,
                () -> service.importResult(request("corrected", null)));
        assertEquals("SUPERSEDES_REQUIRED", required.code());
    }

    @Test
    void importDeniesInactiveMembershipAndWrongRole() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.importResult(request("final", null))).status());
        audited("XCH04_RESULT_IMPORT");

        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.importResult(request("final", null))).status());
        verify(repository, never()).insertReport(any());
    }

    @Test
    void readsARevisionWithItsLineageReviewAndNotifications() {
        authorized("clinician");
        ResultReport current = result(2, ResultKind.CORRECTED, 1, REPORT_1);
        Review review = new Review(REPORT_2, 1, ReviewState.UNREVIEWED, null);
        RecipientNotification notification = new RecipientNotification(NOTE_1, REPORT_2, "owner-a", 1, NOW);
        when(repository.findReportById(REPORT_2)).thenReturn(Optional.of(current));
        when(repository.findReview(REPORT_2)).thenReturn(Optional.of(review));
        when(repository.lineage(ORDER, SOURCE, ACCESSION))
                .thenReturn(List.of(result(1, ResultKind.FINAL, null, null), current));
        when(repository.notifications(REPORT_2)).thenReturn(List.of(notification));

        ResultDetail detail = service.getReport(REPORT_2);
        assertEquals(current, detail.current());
        assertEquals(review, detail.review());
        assertEquals(2, detail.lineage().size());
        assertEquals(List.of(notification), detail.notifications());
        audited("XCH04_RESULT_READ");
    }

    @Test
    void concealsMissingRevisionsAndReviewTasks() {
        authorized("clinician");
        when(repository.findReportById(REPORT_X)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.getReport(REPORT_X)).status());

        when(repository.findReportById(REPORT_1))
                .thenReturn(Optional.of(result(1, ResultKind.FINAL, null, null)));
        when(repository.findReview(REPORT_1)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.getReport(REPORT_1)).status());
    }

    @Test
    void getReportDeniesRolesWithoutClinicalContentAccess() {
        authorized("privacy_auditor");
        ApiException denial = assertThrows(ApiException.class, () -> service.getReport(REPORT_1));
        assertEquals(403, denial.status());
        audited("XCH04_RESULT_READ");
    }

    @Test
    void acknowledgesAnExactRevision() {
        authorized("clinician");
        when(repository.findReview(REPORT_1)).thenReturn(
                Optional.of(new Review(REPORT_1, 1, ReviewState.UNREVIEWED, null)),
                Optional.of(new Review(REPORT_1, 2, ReviewState.ACKNOWLEDGED, NOW)));
        when(repository.updateReview(REPORT_1, 1, NOW)).thenReturn(1);

        Review reviewed = service.acknowledge(REPORT_1, 1);
        assertEquals(ReviewState.ACKNOWLEDGED, reviewed.state());
        assertEquals(2, reviewed.version());
        audited("XCH04_REVIEW_ACK");
    }

    @Test
    void refuseRepeatAndStaleAcknowledgments() {
        authorized("clinician");
        when(repository.findReview(REPORT_1)).thenReturn(
                Optional.of(new Review(REPORT_1, 2, ReviewState.ACKNOWLEDGED, NOW)));
        ApiException repeat = assertThrows(ApiException.class, () -> service.acknowledge(REPORT_1, 2));
        assertEquals(409, repeat.status());
        assertEquals("ALREADY_ACKNOWLEDGED", repeat.code());

        when(repository.findReview(REPORT_2)).thenReturn(
                Optional.of(new Review(REPORT_2, 1, ReviewState.UNREVIEWED, null)));
        when(repository.updateReview(REPORT_2, 7, NOW)).thenReturn(0);
        ApiException stale = assertThrows(ApiException.class, () -> service.acknowledge(REPORT_2, 7));
        assertEquals(412, stale.status());
        audited("XCH04_REVIEW_ACK");
    }

    @Test
    void acknowledgeDeniesWrongRoleAndConcealsMissingTasks() {
        authorized("tenant_admin");
        assertEquals(403, assertThrows(ApiException.class, () -> service.acknowledge(REPORT_1, 1)).status());

        authorized("clinician");
        when(repository.findReview(REPORT_X)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.acknowledge(REPORT_X, 1)).status());
    }

    @Test
    void rejectsNonPositiveAcknowledgmentVersion() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.acknowledge(REPORT_1, 0)).status());
    }

    @Test
    void listsTheReconciliationQueueAndAuditsTheRead() {
        authorized("privacy_auditor");
        ReconciliationEntry entry = new ReconciliationEntry(ENTRY_1, ids(),
                ReconciliationReason.CONFLICT, List.of("sourceId"), "identifier mismatch",
                ReconciliationStatus.OPEN, NOW);
        when(repository.listReconciliation("open")).thenReturn(List.of(entry));
        assertEquals(List.of(entry), service.listReconciliation("open"));
        audited("XCH04_RECONCILIATION_READ");
    }

    @Test
    void resolvedIsAlsoAValidQueueStatus() {
        authorized("tenant_admin");
        when(repository.listReconciliation("resolved")).thenReturn(List.of());
        assertEquals(List.of(), service.listReconciliation("resolved"));
        audited("XCH04_RECONCILIATION_READ");
    }

    @Test
    void rejectsUnknownQueueStatusAndDeniesClinicalReaders() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.listReconciliation("closed")).status());
        authorized("clinician");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.listReconciliation("open")).status());
    }

    @Test
    void validatesEveryBoundaryInputBeforePersistence() {
        authorized("lab_verifier");
        assertEquals("INVALID_INPUT", assertThrows(ApiException.class,
                () -> service.importResult(request(" ", null))).code());
        assertEquals("INVALID_INPUT", assertThrows(ApiException.class,
                () -> service.importResult(new ImportResultRequest(ORDER, "not-a-uuid", SOURCE,
                        ACCESSION, DEST, "final", null, NOW.toString(), "checksum-1"))).code());
        assertEquals("INVALID_KIND", assertThrows(ApiException.class,
                () -> service.importResult(request("unknown", null))).code());
        assertEquals("INVALID_INPUT", assertThrows(ApiException.class,
                () -> service.importResult(new ImportResultRequest(ORDER, PATIENT, SOURCE,
                        ACCESSION, DEST, "final", null, "tomorrow", "checksum-1"))).code());
        assertEquals("INVALID_SUPERSEDES", assertThrows(ApiException.class,
                () -> service.importResult(request("corrected", 0))).code());
        assertEquals("INVALID_SUPERSEDES", assertThrows(ApiException.class,
                () -> service.importResult(request("corrected", 1_000_001))).code());
        verify(repository, never()).insertReport(any());
    }

    @Test
    void rejectsNullInputsAtTheBoundary() {
        authorized("lab_verifier");
        assertEquals("INVALID_INPUT", assertThrows(ApiException.class,
                () -> service.importResult(new ImportResultRequest(null, PATIENT, SOURCE,
                        ACCESSION, DEST, "final", null, NOW.toString(), "checksum-1"))).code());
        verify(repository, never()).insertReport(any());
    }

    @Test
    void rejectsBlankAndOversizedRegistrationFields() {
        authorized("tenant_admin");
        assertEquals(422, assertThrows(ApiException.class, () -> service.registerOrder(
                new RegisterOrderRequest(" ", PATIENT, SOURCE, ACCESSION, DEST, "owner-a", "queue-a"))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.registerOrder(
                new RegisterOrderRequest(ORDER, PATIENT, SOURCE, ACCESSION, DEST,
                        "x".repeat(129), "queue-a"))).status());

        when(repository.findOrder(ORDER)).thenReturn(Optional.empty());
        when(repository.insertOrder(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ExpectedOrder created = service.registerOrder(new RegisterOrderRequest(ORDER, PATIENT, SOURCE,
                ACCESSION, DEST, "owner-a", "q"));
        assertEquals("q", created.coverageQueue(), "a short non-blank queue is valid");
    }

    @Test
    void listCopiesCannotBeMutatedByTheCaller() {
        authorized("clinician");
        ResultReport current = result(1, ResultKind.FINAL, null, null);
        RecipientNotification notification = new RecipientNotification(NOTE_1, REPORT_1, "owner-a", 1, NOW);
        when(repository.findReportById(REPORT_1)).thenReturn(Optional.of(current));
        when(repository.findReview(REPORT_1)).thenReturn(
                Optional.of(new Review(REPORT_1, 1, ReviewState.UNREVIEWED, null)));
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(new java.util.ArrayList<>(List.of(current)));
        when(repository.notifications(REPORT_1)).thenReturn(new java.util.ArrayList<>(List.of(notification)));

        ResultDetail detail = service.getReport(REPORT_1);
        assertEquals(1, detail.lineage().size());
        assertThrows(UnsupportedOperationException.class, () -> detail.lineage().clear());
        assertThrows(UnsupportedOperationException.class, () -> detail.notifications().clear());
    }
}
