package com.aurevia.acceptance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.ImportOutcome;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultModel.ResultKind;
import com.aurevia.exchange.result.ResultModel.ResultReport;
import com.aurevia.exchange.result.ResultModel.Review;
import com.aurevia.exchange.result.ResultModel.ReviewState;
import com.aurevia.exchange.result.ResultRepository;
import com.aurevia.exchange.result.ResultRoutingService;
import com.aurevia.tenant.TenantContext;

/**
 * Gate G2 acceptance for final-report replay integrity (XCH-04, G2).
 *
 * <p>A duplicate or a reordered older event resolves to the stored revision and
 * never inserts a second report or overwrites the final report; a distinct
 * clinical payload is rejected instead of being discarded; and a correction
 * appends a new revision and reopens a review that a human had acknowledged while
 * leaving the acknowledged original intact. The repository mock holds only
 * synthetic domain state; no patient data is used.</p>
 */
class FinalReportReplayIntegrityAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "labverifier-g2";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ORDER = "order-g2";
    private static final String SOURCE = "lab-g2";
    private static final String ACCESSION = "ACC-G2";
    private static final String DEST = "clinic-g2";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final String REPORT_1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
    private static final String REPORT_2 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";

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
        TenantContext.set(new TenantContext(ORG, ACTOR, "XCH04_RESULT_ROUTING", "corr-g2-report"));
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("lab_verifier", "clinician"));
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(repository.priorRecipients(ORDER)).thenReturn(List.of());
        when(repository.findReview(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void duplicateFinalDoesNotDuplicateOrOverwriteTheFinalReport() {
        // XCH-04 / G2: a repeated event with the same clinical payload returns the
        // stored revision and inserts no second report, review or notification.
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of());
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.empty());
        ImportOutcome first = service.importResult(request("final", null, "checksum-1"));
        ResultReport stored = first.report();
        assertNotNull(stored);
        assertEquals(1, stored.version());

        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(stored));
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(stored));
        ImportOutcome replay = service.importResult(request("final", null, "checksum-1"));

        assertEquals(stored, replay.report());
        assertNull(replay.reconciliation());
        verify(repository, times(1)).insertReport(any());
        verify(repository, times(1)).insertReview(anyString());
        verify(repository, never()).insertNotification(any());
        audited("XCH04_RESULT_REPLAY");
    }

    @Test
    void reorderedOlderFinalDoesNotOverwriteTheFinalReport() {
        // XCH-04 / G2: after a correction advances the lineage, a reordered event
        // carrying the earlier final payload resolves to that stored final and
        // never overwrites the corrected tip or creates a new version.
        ResultReport first = result(1, ResultKind.FINAL, null, null);
        ResultReport correction = result(2, ResultKind.CORRECTED, 1, REPORT_1);
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(first, correction));

        ImportOutcome outcome = service.importResult(request("final", null, "checksum-1"));

        assertEquals(first, outcome.report(), "the reordered older event resolves to its stored final");
        assertNull(outcome.reconciliation());
        verify(repository, never()).insertReport(any());
        audited("XCH04_RESULT_REPLAY");
    }

    @Test
    void distinctPayloadReplayingAStoredVersionIsRejectedNotDiscarded() {
        // XCH-04 / G2: a version key is not an idempotency key. A different payload
        // against the same stored version is a conflict, never a silent overwrite.
        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of());
        ResultReport stored = result(1, ResultKind.FINAL, null, null);
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(stored));

        ApiException conflict = assertThrows(ApiException.class,
                () -> service.importResult(request("final", null, "different-payload")));

        assertEquals(409, conflict.status());
        assertEquals("PAYLOAD_CONFLICT", conflict.code());
        verify(repository, never()).insertReport(any());
        audited("XCH04_RESULT_CONFLICT");
    }

    @Test
    void correctionReopensReviewAfterHumanAcknowledgment() {
        // XCH-04 / G2: a human acknowledges the final revision, then a correction
        // appends a new unreviewed revision, notifies prior recipients and reopens
        // review, while the acknowledged original and its attestation stay intact.
        ResultReport prior = result(1, ResultKind.FINAL, null, null);
        when(repository.findReview(REPORT_1)).thenReturn(
                Optional.of(new Review(REPORT_1, 1, ReviewState.UNREVIEWED, null)),
                Optional.of(new Review(REPORT_1, 1, ReviewState.ACKNOWLEDGED, NOW)),
                Optional.of(new Review(REPORT_1, 1, ReviewState.ACKNOWLEDGED, NOW)));
        when(repository.updateReview(REPORT_1, 1, NOW)).thenReturn(1);

        Review acknowledged = service.acknowledge(REPORT_1, 1);
        assertEquals(ReviewState.ACKNOWLEDGED, acknowledged.state());

        when(repository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(prior));
        when(repository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(prior));
        when(repository.priorRecipients(ORDER)).thenReturn(List.of("owner-g2", "previous-owner"));

        ImportOutcome outcome = service.importResult(request("corrected", 1, "checksum-1"));
        ResultReport correction = outcome.report();
        assertNotNull(correction);
        assertEquals(2, correction.version());
        assertEquals(ResultKind.CORRECTED, correction.kind());
        assertEquals(REPORT_1, correction.previousId(), "the correction links the acknowledged original");
        verify(repository).insertReview(correction.id());
        verify(repository, times(2)).insertNotification(any());
        audited("XCH04_REVIEW_REOPENED");
    }

    private static ResultIdentifiers ids() {
        return new ResultIdentifiers(PATIENT, SOURCE, ACCESSION, DEST, ORDER);
    }

    private static ExpectedOrder order() {
        return new ExpectedOrder(ORDER, ids(), "owner-g2", "queue-g2");
    }

    private static ResultReport result(int version, ResultKind kind, Integer supersedes, String previousId) {
        String id = version == 1 ? REPORT_1 : REPORT_2;
        return new ResultReport(id, version, ORDER, ids(), kind, supersedes, previousId,
                "owner-g2", "queue-g2", NOW, NOW, "checksum-1");
    }

    private static ImportResultRequest request(String kind, Integer supersedes, String checksum) {
        return new ImportResultRequest(ORDER, PATIENT, SOURCE, ACCESSION, DEST, kind, supersedes,
                NOW.toString(), checksum);
    }

    private void audited(String action) {
        verify(audit, atLeastOnce()).append(any(com.aurevia.tenant.TenantContext.class),
                eq("XCH04_RESULT_ROUTING"), eq(action), anyString(), eq("EXCHANGE"), anyString(), anyString());
    }
}
