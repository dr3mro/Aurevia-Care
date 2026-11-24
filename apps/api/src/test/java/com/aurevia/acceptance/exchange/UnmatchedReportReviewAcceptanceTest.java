package com.aurevia.acceptance.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.result.ResultActionPolicy;
import com.aurevia.exchange.result.ResultCommands.ImportResultRequest;
import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.ImportOutcome;
import com.aurevia.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.exchange.result.ResultModel.ReconciliationReason;
import com.aurevia.exchange.result.ResultModel.ReconciliationStatus;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultRepository;
import com.aurevia.exchange.result.ResultRoutingService;
import com.aurevia.tenant.TenantContext;

/**
 * Gate G2 acceptance for an unmatched result report (XCH-04, LAB-01, G2).
 *
 * <p>An inbound report whose patient, source, accession, destination and order
 * identifiers cannot be matched to a released order is never imported or linked
 * to a chart. It is parked as an OPEN reconciliation entry that a reviewer can
 * list, so the report waits for review instead of being attached to the wrong
 * patient. The repository mock holds only synthetic domain state.</p>
 */
class UnmatchedReportReviewAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "labverifier-g2";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ORDER = "order-unmatched-g2";
    private static final String SOURCE = "lab-g2";
    private static final String ACCESSION = "ACC-UNKNOWN";
    private static final String DEST = "clinic-g2";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

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
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "XCH04_RESULT_ROUTING", "corr-g2-unmatched"));
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("lab_verifier"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void unmatchedResultWaitsInTheOpenReconciliationQueue() {
        // XCH-04 / LAB-01 / G2: no expected order exists, so the report is diverted
        // to reconciliation with no imported revision and no clinical review task.
        when(repository.findOrder(ORDER)).thenReturn(Optional.empty());

        ImportOutcome outcome = service.importResult(request());
        when(repository.listReconciliation("open")).thenReturn(List.of(outcome.reconciliation()));
        assertNull(outcome.report(), "an unmatched report is never imported");
        assertNotNull(outcome.reconciliation());
        ReconciliationEntry entry = outcome.reconciliation();
        assertEquals(ReconciliationReason.UNMATCHED, entry.reason());
        assertEquals(ReconciliationStatus.OPEN, entry.status());
        assertTrue(entry.detail().contains("no expected order"));
        verify(repository, never()).insertReport(any());
        verify(repository, never()).insertReview(anyString());
        audited("XCH04_RESULT_RECONCILED");

        List<ReconciliationEntry> waiting = service.listReconciliation("open");
        assertEquals(1, waiting.size());
        assertEquals(entry, waiting.get(0), "the unmatched report waits for a reviewer");
        audited("XCH04_RECONCILIATION_READ");
    }

    @Test
    void conflictingIdentifiersAreDivertedRatherThanLinkedToTheWrongOrder() {
        // XCH-04 / LAB-01 / G2: an order exists but the identifiers disagree, so
        // the report is still parked for reconciliation, not linked elsewhere.
        ResultIdentifiers foreign = new ResultIdentifiers(PATIENT, "other-source", ACCESSION, DEST, ORDER);
        when(repository.findOrder(ORDER)).thenReturn(
                Optional.of(new ExpectedOrder(ORDER, foreign, "owner-g2", "queue-g2")));

        ImportOutcome outcome = service.importResult(request());

        assertNull(outcome.report());
        assertEquals(ReconciliationReason.CONFLICT, outcome.reconciliation().reason());
        assertEquals(List.of("sourceId"), outcome.reconciliation().mismatches());
        verify(repository, never()).insertReport(any());
        audited("XCH04_RESULT_RECONCILED");
    }

    private static ImportResultRequest request() {
        return new ImportResultRequest(ORDER, PATIENT, SOURCE, ACCESSION, DEST, "final", null,
                NOW.toString(), "checksum-g2");
    }

    private void audited(String action) {
        verify(audit, atLeastOnce()).append(any(TenantContext.class), eq("XCH04_RESULT_ROUTING"),
                eq(action), anyString(), eq("EXCHANGE"), anyString(), anyString());
    }
}
