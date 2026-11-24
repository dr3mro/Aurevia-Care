package com.aurevia.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.ImportOutcome;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultModel.ResultKind;
import com.aurevia.exchange.result.ResultModel.ResultReport;
import com.aurevia.exchange.result.ResultRepository;
import com.aurevia.exchange.result.ResultRoutingService;

import static com.aurevia.acceptance.scenarios.ScenarioSupport.CLINIC_ALPHA;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.CLOCK;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.NOW;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PARTNER_LAB;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PATIENT;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.bind;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.clear;

/**
 * Section 11 cross-cutting scenario: failed exchange after commit (ARC-05, XCH-01, XCH-04).
 *
 * <p>The committed order result and its review are held in the exchange store,
 * not in service memory, so a fresh service instance replaying the review-queue
 * event produces exactly one downstream effect and never inserts a second
 * report or notification. A distinct payload against a stored version is a
 * conflict, and a correction creates exactly one new clinical task for the
 * intended revision. Physical survival across a process restart is proven by
 * the database integration suite; this scenario proves the replay semantics
 * that make such a restart safe.</p>
 */
class FailedExchangeAfterCommitScenarioAcceptanceTest {

    private static final String ORDER = "order-failed-exchange";
    private static final String ACCESSION = "ACC-FAILED";
    private static final String REPORT_1 = "77777777-7777-7777-7777-777777777771";
    private static final String REPORT_2 = "77777777-7777-7777-7777-777777777772";

    private ResultRepository repository;
    private ResultRoutingService service;

    @BeforeEach
    void setUp() {
        bind(CLINIC_ALPHA, ScenarioSupport.LAB_VERIFIER);
        repository = mock(ResultRepository.class);
        service = newService();
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        when(repository.activeRoles(anyString())).thenReturn(List.of("lab_verifier", "clinician"));
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(new ExpectedOrder(ORDER, ids(), "owner-1", "queue-1")));
        when(repository.insertReport(any())).thenReturn(true);
        when(repository.findReview(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    private ResultRoutingService newService() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        return new ResultRoutingService(repository, new ResultActionPolicy(), transactions,
                mock(AuditAppender.class), CLOCK);
    }

    @Test
    void replayAfterCommitProducesExactlyOneDownstreamEffect() {
        // XCH-01: the first import commits the report and its review. The
        // committed state lives in the repository; a fresh service instance
        // replaying the same event resolves to that stored revision only.
        when(repository.lineage(ORDER, PARTNER_LAB, ACCESSION)).thenReturn(List.of());
        when(repository.findReport(ORDER, PARTNER_LAB, ACCESSION, 1)).thenReturn(Optional.empty());
        ImportOutcome first = service.importResult(request("final", "checksum-committed"));
        ResultReport stored = first.report();
        assertNotNull(stored);
        assertEquals(1, stored.version());

        when(repository.lineage(ORDER, PARTNER_LAB, ACCESSION)).thenReturn(List.of(stored));
        when(repository.findReport(ORDER, PARTNER_LAB, ACCESSION, 1)).thenReturn(Optional.of(stored));

        // A new service instance (simulating a process that lost its heap) sees
        // the same committed result and still emits no second effect.
        ResultRoutingService restarted = newService();
        ImportOutcome replay = restarted.importResult(request("final", "checksum-committed"));

        assertEquals(stored, replay.report());
        assertNull(replay.reconciliation());
        verify(repository, times(1)).insertReport(any());
        verify(repository, times(1)).insertReview(anyString());
        verify(repository, never()).insertNotification(any());
    }

    @Test
    void distinctPayloadReplayingACommittedVersionIsAConflict() {
        // XCH-04: a version key is not an idempotency key. Reusing a stored
        // version with different clinical content is refused, not silently
        // overwritten, so a corrupted retry cannot change the committed fact.
        ResultReport stored = result(1, ResultKind.FINAL, null, null);
        when(repository.findReport(ORDER, PARTNER_LAB, ACCESSION, 1)).thenReturn(Optional.of(stored));

        ApiException conflict = assertThrows(ApiException.class,
                () -> service.importResult(request("final", "checksum-different")));

        assertEquals(409, conflict.status());
        assertEquals("PAYLOAD_CONFLICT", conflict.code());
        verify(repository, never()).insertReport(any());
    }

    @Test
    void correctionCreatesOneClinicalTaskForTheIntendedRevision() {
        // XCH-04: after a human acknowledges the final revision, a correction
        // appends a new revision and opens exactly one new review (clinical task)
        // while the acknowledged original stays intact.
        ResultReport prior = result(1, ResultKind.FINAL, null, null);
        when(repository.findReview(REPORT_1)).thenReturn(
                Optional.of(new com.aurevia.exchange.result.ResultModel.Review(
                        REPORT_1, 1, com.aurevia.exchange.result.ResultModel.ReviewState.UNREVIEWED, null)),
                Optional.of(new com.aurevia.exchange.result.ResultModel.Review(
                        REPORT_1, 1, com.aurevia.exchange.result.ResultModel.ReviewState.ACKNOWLEDGED, NOW)),
                Optional.of(new com.aurevia.exchange.result.ResultModel.Review(
                        REPORT_1, 1, com.aurevia.exchange.result.ResultModel.ReviewState.ACKNOWLEDGED, NOW)));
        when(repository.updateReview(REPORT_1, 1, NOW)).thenReturn(1);
        service.acknowledge(REPORT_1, 1);

        when(repository.lineage(ORDER, PARTNER_LAB, ACCESSION)).thenReturn(List.of(prior));
        when(repository.findReport(ORDER, PARTNER_LAB, ACCESSION, 1)).thenReturn(Optional.of(prior));
        when(repository.priorRecipients(ORDER)).thenReturn(List.of("owner-1", "previous-owner"));

        ImportOutcome outcome = service.importResult(new ImportResultRequest(ORDER, PATIENT,
                PARTNER_LAB, ACCESSION, CLINIC_ALPHA, "corrected", 1, NOW.toString(), "checksum-corrected"));
        ResultReport correction = outcome.report();
        assertNotNull(correction);
        assertEquals(2, correction.version());
        assertEquals(ResultKind.CORRECTED, correction.kind());
        assertEquals(REPORT_1, correction.previousId());
        verify(repository, times(1)).insertReview(correction.id());
        verify(repository, times(2)).insertNotification(any());
    }

    private static ResultIdentifiers ids() {
        return new ResultIdentifiers(PATIENT, PARTNER_LAB, ACCESSION, CLINIC_ALPHA, ORDER);
    }

    private static ResultReport result(int version, ResultKind kind, Integer supersedes, String previousId) {
        String id = version == 1 ? REPORT_1 : REPORT_2;
        return new ResultReport(id, version, ORDER, ids(), kind, supersedes, previousId,
                "owner-1", "queue-1", NOW, NOW, "checksum-committed");
    }

    private static ImportResultRequest request(String kind, String checksum) {
        return new ImportResultRequest(ORDER, PATIENT, PARTNER_LAB, ACCESSION, CLINIC_ALPHA,
                kind, null, NOW.toString(), checksum);
    }
}
