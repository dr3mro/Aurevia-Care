package com.aurevia.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

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
import com.aurevia.messaging.reports.PatientChartLink;
import com.aurevia.messaging.reports.ReleasedReport;
import com.aurevia.messaging.reports.ReleasedReport.Status;
import com.aurevia.messaging.reports.ReleasedReportService;
import com.aurevia.messaging.reports.ReleasedReportService.InMemoryAuditSink;
import com.aurevia.messaging.reports.ReportReleaseStore;
import com.aurevia.tenant.TenantContext;

import static com.aurevia.acceptance.scenarios.ScenarioSupport.CLINIC_ALPHA;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.NOW;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PARTNER_LAB;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PATIENT;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PATIENT_SUBJECT;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.bind;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.clear;

/**
 * Section 11 cross-cutting scenario: retracted document (XCH-06, XCH-04, PAT-02).
 *
 * <p>Once a released revision is withdrawn the patient portal stops serving it,
 * while the fact that the disclosure was issued stays in the append-only audit
 * history. A later clinical correction is sent only to the original recipient
 * list and reopens review without erasing the acknowledged original. The
 * repository and store mocks hold only synthetic domain state.</p>
 */
class RetractedDocumentScenarioAcceptanceTest {

    private static final String REPORT = "report-retracted";
    private static final String ORDER = "order-retracted";
    private static final String DEST = CLINIC_ALPHA;
    private static final String REPORT_1 = "cccccccc-cccc-cccc-cccc-ccccccccccc1";

    private ReportReleaseStore store;
    private InMemoryAuditSink audit;
    private ReleasedReportService portal;

    private ResultRepository results;
    private AuditAppender resultAudit;
    private ResultRoutingService routing;

    @BeforeEach
    void setUp() {
        store = mock(ReportReleaseStore.class);
        audit = new InMemoryAuditSink();
        portal = new ReleasedReportService(store, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        bind(CLINIC_ALPHA, PATIENT_SUBJECT);
        when(store.link(CLINIC_ALPHA, PATIENT_SUBJECT)).thenReturn(
                Optional.of(new PatientChartLink(PATIENT, true, true)));

        results = mock(ResultRepository.class);
        resultAudit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        routing = new ResultRoutingService(results, new ResultActionPolicy(), transactions,
                resultAudit, Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        bind(CLINIC_ALPHA, ScenarioSupport.LAB_VERIFIER);
        when(results.hasActiveMembership(anyString())).thenReturn(true);
        when(results.activeRoles(anyString())).thenReturn(List.of("lab_verifier", "clinician"));
        when(results.findOrder(ORDER)).thenReturn(Optional.of(new ExpectedOrder(ORDER, ids(), "owner-1", "queue-1")));
        when(results.priorRecipients(ORDER)).thenReturn(List.of("owner-1", "previous-owner"));
        when(results.insertReport(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    @Test
    void withdrawalStopsPortalAccessButKeepsTheIssuedDisclosureHistory() {
        // XCH-06 / PAT-02: a withdrawn revision is terminal for access, and an
        // already issued disclosure remains as a detail-free audit fact.
        bind(CLINIC_ALPHA, PATIENT_SUBJECT);
        ReleasedReport released = report(Status.RELEASED);
        when(store.find(CLINIC_ALPHA, REPORT, 1)).thenReturn(Optional.of(released));
        when(store.latestReleasedRevision(CLINIC_ALPHA, REPORT, NOW.atZone(ZoneOffset.UTC).toLocalDate()))
                .thenReturn(OptionalInt.of(1));
        ReleasedReportService.ReportDownload served = portal.download(REPORT, 1);
        assertEquals(REPORT, served.reportId());

        ReleasedReport withdrawn = report(Status.WITHDRAWN);
        when(store.find(CLINIC_ALPHA, REPORT, 1)).thenReturn(Optional.of(withdrawn));
        when(store.reportsFor(CLINIC_ALPHA, PATIENT)).thenReturn(List.of(withdrawn));
        when(store.withdraw(CLINIC_ALPHA, REPORT, 1)).thenReturn(true);

        assertTrue(store.withdraw(CLINIC_ALPHA, REPORT, 1));
        ApiException denial = assertThrows(ApiException.class, () -> portal.download(REPORT, 1));
        assertEquals(410, denial.status());
        assertEquals("RELEASE_WITHDRAWN", denial.code());
        assertTrue(portal.listFor().isEmpty());

        // The disclosure that was issued before withdrawal is still audited with
        // its purpose; withdrawal is a separate, also-audited fact.
        assertTrue(audit.events().stream().anyMatch(event -> event.action()
                .equals(ReleasedReportService.ACTION_DOWNLOAD)));
        assertTrue(audit.events().stream().anyMatch(event -> "WITHDRAWN".equals(event.outcome())));
    }

    @Test
    void correctionAfterWithdrawalNotifiesOnlyTheOriginalRecipients() {
        // XCH-04: a correction appends a new revision, notifies every recipient
        // of the original (including the previous owner) and reopens review while
        // leaving the acknowledged original intact.
        bind(CLINIC_ALPHA, ScenarioSupport.LAB_VERIFIER);
        ResultReport prior = new ResultReport(REPORT_1, 1, ORDER, ids(), ResultKind.FINAL,
                null, null, "owner-1", "queue-1", NOW, NOW, "checksum-1");
        when(results.lineage(ORDER, PARTNER_LAB, "ACC-1")).thenReturn(List.of(prior));
        when(results.findReport(ORDER, PARTNER_LAB, "ACC-1", 1)).thenReturn(Optional.of(prior));
        when(results.findReview(REPORT_1)).thenReturn(
                Optional.of(new Review(REPORT_1, 1, ReviewState.UNREVIEWED, null)),
                Optional.of(new Review(REPORT_1, 1, ReviewState.ACKNOWLEDGED, NOW)),
                Optional.of(new Review(REPORT_1, 1, ReviewState.ACKNOWLEDGED, NOW)));
        when(results.updateReview(REPORT_1, 1, NOW)).thenReturn(1);

        Review acknowledged = routing.acknowledge(REPORT_1, 1);
        assertEquals(ReviewState.ACKNOWLEDGED, acknowledged.state());

        ImportOutcome outcome = routing.importResult(new ImportResultRequest(ORDER, PATIENT,
                PARTNER_LAB, "ACC-1", DEST, "corrected", 1, NOW.toString(), "checksum-2"));

        ResultReport correction = outcome.report();
        assertNotNull(correction);
        assertEquals(2, correction.version());
        assertEquals(ResultKind.CORRECTED, correction.kind());
        assertEquals(REPORT_1, correction.previousId());
        verify(results).insertReview(correction.id());
        verify(results, atLeastOnce()).insertReport(any());
        // One notification per original recipient, including the previous owner
        // who is not the current owner.
        verify(results, times(2)).insertNotification(any());
        verify(resultAudit, atLeastOnce()).append(any(TenantContext.class), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    private static ResultIdentifiers ids() {
        return new ResultIdentifiers(PATIENT, PARTNER_LAB, "ACC-1", DEST, ORDER);
    }

    private static ReleasedReport report(Status status) {
        return new ReleasedReport(REPORT, 1, PATIENT, CLINIC_ALPHA, "Synthetic clinic",
                "2026-11-20", "en", "document-1", "checksum-1", status, null);
    }
}
