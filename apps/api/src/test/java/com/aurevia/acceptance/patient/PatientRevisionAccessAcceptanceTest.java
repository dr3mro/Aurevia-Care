package com.aurevia.acceptance.patient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.reports.PatientChartLink;
import com.aurevia.messaging.reports.ReleasedReport;
import com.aurevia.messaging.reports.ReleasedReport.Status;
import com.aurevia.messaging.reports.ReleasedReportService;
import com.aurevia.messaging.reports.ReleasedReportService.InMemoryAuditSink;
import com.aurevia.messaging.reports.ReleasedReportService.ReportDownload;
import com.aurevia.messaging.reports.ReleasedReportService.ReleasedReportView;
import com.aurevia.messaging.reports.ReportReleaseStore;
import com.aurevia.tenant.TenantContext;

/**
 * Gate G3 acceptance for patient report access.
 *
 * <p>Requirements proved: PAT-02 (only authorized, effective, current revisions
 * are listed or downloaded), XCH-06 (a withdrawn release stops future access
 * while history is retained) and PAT-03 / IAM-05 (the verified chart link is
 * re-derived on every protected action). All fixtures are synthetic.</p>
 */
class PatientRevisionAccessAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "syn-patient-revisions";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_PATIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String REPORT = "report-1";
    private static final String CORRELATION = "corr-g3-patient-revisions";
    private static final LocalDate TODAY = LocalDate.parse("2026-06-15");
    private static final Clock NOW = Clock.fixed(TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private ReportReleaseStore store;
    private InMemoryAuditSink audit;
    private ReleasedReportService service;

    @BeforeEach
    void setUp() {
        store = mock(ReportReleaseStore.class);
        audit = new InMemoryAuditSink();
        service = new ReleasedReportService(store, audit, NOW);
        TenantContext.set(new TenantContext(ORG, SUBJECT, TenantContext.PURPOSE, CORRELATION));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void patientSeesOnlyAuthorizedEffectiveRevisions() {
        // PAT-02: a held revision and a future-dated revision are never listed.
        when(store.link(ORG, SUBJECT)).thenReturn(Optional.of(new PatientChartLink(PATIENT, true, true)));
        when(store.reportsFor(ORG, PATIENT)).thenReturn(List.of(
                report(1, Status.RELEASED, "2026-06-10", PATIENT),
                report(2, Status.HELD, "2026-06-12", PATIENT),
                report(3, Status.RELEASED, "2026-07-01", PATIENT)));

        List<ReleasedReportView> views = service.listFor();

        assertEquals(1, views.size());
        assertEquals(1, views.get(0).revision());
        assertEquals("2026-06-10", views.get(0).effectiveDate());
        assertEquals("Synthetic clinic", views.get(0).issuingOrganization());
        assertTrue(audit.events().stream().anyMatch(event -> event.action().equals(ReleasedReportService.ACTION_LIST)
                && event.outcome().equals("SUCCESS") && event.purpose().equals(ReleasedReportService.PURPOSE)));
    }

    @Test
    void downloadOfAnotherPatientsRevisionIsConcealed() {
        // PAT-02: even a store that hands back a foreign revision cannot expose it.
        when(store.link(ORG, SUBJECT)).thenReturn(Optional.of(new PatientChartLink(PATIENT, true, true)));
        when(store.find(ORG, REPORT, 1)).thenReturn(
                Optional.of(report(1, Status.RELEASED, "2026-06-10", OTHER_PATIENT)));

        ApiException denial = assertThrows(ApiException.class, () -> service.download(REPORT, 1));
        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
        assertTrue(audit.events().stream().anyMatch(event -> event.outcome().equals("DENIED")));

        // Positive control: the same revision of the linked chart is served.
        when(store.find(ORG, REPORT, 1)).thenReturn(
                Optional.of(report(1, Status.RELEASED, "2026-06-10", PATIENT)));
        when(store.latestReleasedRevision(ORG, REPORT, TODAY)).thenReturn(OptionalInt.of(1));
        ReportDownload download = service.download(REPORT, 1);
        assertEquals(REPORT, download.reportId());
        assertEquals(1, download.revision());
        assertEquals("document-1", download.documentId());
    }

    @Test
    void withdrawnReleaseStopsFutureAccess() {
        // XCH-06 / PAT-02: withdrawal is terminal for access; the fact history remains.
        when(store.link(ORG, SUBJECT)).thenReturn(Optional.of(new PatientChartLink(PATIENT, true, true)));
        ReleasedReport withdrawn = report(1, Status.WITHDRAWN, "2026-06-10", PATIENT);
        when(store.find(ORG, REPORT, 1)).thenReturn(Optional.of(withdrawn));
        when(store.reportsFor(ORG, PATIENT)).thenReturn(List.of(withdrawn));
        when(store.withdraw(ORG, REPORT, 1)).thenReturn(true);

        assertTrue(store.withdraw(ORG, REPORT, 1));
        ApiException denial = assertThrows(ApiException.class, () -> service.download(REPORT, 1));
        assertEquals(410, denial.status());
        assertEquals("RELEASE_WITHDRAWN", denial.code());
        assertTrue(audit.events().stream().anyMatch(event -> event.outcome().equals("WITHDRAWN")));
        assertTrue(service.listFor().isEmpty());
    }

    @Test
    void unverifiedOrUnlinkedChartDeniesAccess() {
        // PAT-03 / IAM-05: a cached login is not a substitute for a live verified link.
        when(store.link(ORG, SUBJECT)).thenReturn(Optional.of(new PatientChartLink(PATIENT, false, true)));
        ApiException unverified = assertThrows(ApiException.class, () -> service.listFor());
        assertEquals(403, unverified.status());
        assertEquals("FORBIDDEN", unverified.code());

        when(store.link(ORG, SUBJECT)).thenReturn(Optional.of(new PatientChartLink(PATIENT, true, false)));
        ApiException unlinked = assertThrows(ApiException.class, () -> service.download(REPORT, 1));
        assertEquals(403, unlinked.status());
        assertEquals("FORBIDDEN", unlinked.code());
        assertEquals(2, audit.events().stream().filter(event -> event.outcome().equals("DENIED")).count());
    }

    private static ReleasedReport report(int revision, Status status, String effectiveDate, String patientId) {
        return new ReleasedReport(REPORT, revision, patientId, ORG, "Synthetic clinic", effectiveDate,
                "en", "document-" + revision, "checksum-" + revision, status, null);
    }
}
