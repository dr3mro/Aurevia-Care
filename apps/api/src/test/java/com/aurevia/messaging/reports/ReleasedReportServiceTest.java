package com.aurevia.messaging.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.reports.ReleasedReport.CorrectionContext;
import com.aurevia.messaging.reports.ReleasedReport.Status;
import com.aurevia.messaging.reports.ReleasedReportService.AuditRecord;
import com.aurevia.messaging.reports.ReleasedReportService.InMemoryAuditSink;
import com.aurevia.messaging.reports.ReleasedReportService.ReportDownload;
import com.aurevia.messaging.reports.ReleasedReportService.ReleasedReportView;
import com.aurevia.tenant.TenantContext;

/** S-G3-02 red-first acceptance tests for PAT-02 released-report access. */
class ReleasedReportServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "patient-subject-1";
    private static final String PATIENT = "patient-1";
    private static final String REPORT = "report-1";
    private static final String CORRELATION = "corr-g3-02";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    private InMemoryReportReleaseStore store;
    private InMemoryAuditSink audit;
    private ReleasedReportService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryReportReleaseStore();
        audit = new InMemoryAuditSink();
        service = new ReleasedReportService(store, audit, NOW);
        context(ORG, SUBJECT);
        link(PATIENT, true, true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void context(String organization, String subject) {
        TenantContext.set(new TenantContext(organization, subject, TenantContext.PURPOSE, CORRELATION));
    }

    private void link(String patientId, boolean verified, boolean active) {
        store.putLink(ORG, SUBJECT, new PatientChartLink(patientId, verified, active));
    }

    private ReleasedReport report(int revision, Status status, String effectiveDate) {
        return report(revision, status, effectiveDate, PATIENT, ORG, null);
    }

    private ReleasedReport report(int revision, Status status, String effectiveDate, String patientId,
                                  String organizationId, CorrectionContext correction) {
        return new ReleasedReport(REPORT, revision, patientId, organizationId, "Clinic A", effectiveDate,
                "en", "document-" + revision, "checksum-" + revision, status, correction);
    }

    @Test
    void valueTypesExposeTheirParts() {
        CorrectionContext correction = new CorrectionContext(1, "corrected glucose value");
        ReleasedReport report = report(2, Status.RELEASED, "2026-06-10", PATIENT, ORG, correction);
        assertEquals(REPORT, report.id());
        assertEquals(2, report.revision());
        assertEquals(PATIENT, report.patientId());
        assertEquals(ORG, report.organizationId());
        assertEquals("Clinic A", report.issuingOrganization());
        assertEquals("2026-06-10", report.effectiveDate());
        assertEquals("en", report.language());
        assertEquals("document-2", report.documentId());
        assertEquals("checksum-2", report.checksum());
        assertEquals(Status.RELEASED, report.status());
        assertEquals(correction, report.correction());
        assertEquals(report, report(2, Status.RELEASED, "2026-06-10", PATIENT, ORG, correction));
        assertEquals(report.hashCode(), report(2, Status.RELEASED, "2026-06-10", PATIENT, ORG, correction).hashCode());
        assertEquals(report.toString(), report(2, Status.RELEASED, "2026-06-10", PATIENT, ORG, correction).toString());
        assertEquals(1, correction.supersedesRevision());
        assertEquals("corrected glucose value", correction.reason());
        assertEquals(correction, new CorrectionContext(1, "corrected glucose value"));
        assertEquals(correction.hashCode(), new CorrectionContext(1, "corrected glucose value").hashCode());
        assertEquals(correction.toString(), new CorrectionContext(1, "corrected glucose value").toString());
        assertNotEquals(correction, new CorrectionContext(1, "other"));

        PatientChartLink link = new PatientChartLink(PATIENT, true, true);
        assertEquals(PATIENT, link.patientId());
        assertTrue(link.verified());
        assertTrue(link.active());
        assertEquals(link, new PatientChartLink(PATIENT, true, true));
        assertEquals(link.hashCode(), new PatientChartLink(PATIENT, true, true).hashCode());
        assertEquals(link.toString(), new PatientChartLink(PATIENT, true, true).toString());
        assertNotEquals(link, new PatientChartLink(PATIENT, false, true));

        assertEquals(3, Status.values().length);
        assertEquals(Status.WITHDRAWN, Status.valueOf("WITHDRAWN"));
        assertEquals(Status.HELD, Status.valueOf("HELD"));

        ReportDownload download = new ReportDownload(REPORT, 2, "document-2", "checksum-2", "en");
        assertEquals(REPORT, download.reportId());
        assertEquals(2, download.revision());
        assertEquals("document-2", download.documentId());
        assertEquals("checksum-2", download.checksum());
        assertEquals("en", download.language());
        assertEquals(download, new ReportDownload(REPORT, 2, "document-2", "checksum-2", "en"));
        assertEquals(download.hashCode(), new ReportDownload(REPORT, 2, "document-2", "checksum-2", "en").hashCode());
        assertEquals(download.toString(), new ReportDownload(REPORT, 2, "document-2", "checksum-2", "en").toString());

        ReleasedReportView view = ReleasedReportView.from(report);
        assertEquals(REPORT, view.reportId());
        assertEquals(2, view.revision());
        assertEquals("Clinic A", view.issuingOrganization());
        assertEquals("2026-06-10", view.effectiveDate());
        assertEquals("en", view.language());
        assertEquals("corrected glucose value", view.correctionReason());
        assertEquals(1, view.supersedesRevision());
        assertEquals(view, ReleasedReportView.from(report));
        assertEquals(view.hashCode(), ReleasedReportView.from(report).hashCode());
        assertEquals(view.toString(), ReleasedReportView.from(report).toString());

        Instant occurred = Instant.parse("2026-06-15T12:00:00Z");
        AuditRecord event = new AuditRecord("REPORT_DOWNLOAD", REPORT, "SUCCESS", SUBJECT, ORG,
                CORRELATION, ReleasedReportService.PURPOSE, ReleasedReportService.SCOPE, occurred);
        assertEquals("REPORT_DOWNLOAD", event.action());
        assertEquals(REPORT, event.resourceId());
        assertEquals("SUCCESS", event.outcome());
        assertEquals(SUBJECT, event.subject());
        assertEquals(ORG, event.organizationId());
        assertEquals(CORRELATION, event.correlationId());
        assertEquals(ReleasedReportService.PURPOSE, event.purpose());
        assertEquals(ReleasedReportService.SCOPE, event.scope());
        assertEquals(occurred, event.occurredAt());
        assertEquals(event, new AuditRecord("REPORT_DOWNLOAD", REPORT, "SUCCESS", SUBJECT, ORG,
                CORRELATION, ReleasedReportService.PURPOSE, ReleasedReportService.SCOPE, occurred));
        assertEquals(event.hashCode(), new AuditRecord("REPORT_DOWNLOAD", REPORT, "SUCCESS", SUBJECT, ORG,
                CORRELATION, ReleasedReportService.PURPOSE, ReleasedReportService.SCOPE, occurred).hashCode());
        assertEquals(event.toString(), new AuditRecord("REPORT_DOWNLOAD", REPORT, "SUCCESS", SUBJECT, ORG,
                CORRELATION, ReleasedReportService.PURPOSE, ReleasedReportService.SCOPE, occurred).toString());
    }

    @Test
    void listRequiresTenantContext() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class, service::listFor).status());
        assertTrue(audit.events().isEmpty());
    }

    @Test
    void listDeniesAbsentUnverifiedAndInactiveLinks() {
        context(ORG, "unknown-subject");
        assertEquals(403, assertThrows(ApiException.class, service::listFor).status());
        assertEquals("DENIED", audit.events().get(0).outcome());
        context(ORG, SUBJECT);

        link(PATIENT, false, true);
        assertEquals(403, assertThrows(ApiException.class, service::listFor).status());
        link(PATIENT, true, false);
        assertEquals(403, assertThrows(ApiException.class, service::listFor).status());
        assertEquals(3, audit.events().size());
        assertTrue(audit.events().stream().allMatch(event -> "DENIED".equals(event.outcome())));
    }

    @Test
    void listShowsRevisionIssuerEffectiveDateAndCorrectionContextInOrder() {
        store.putReport(report(1, Status.RELEASED, "2026-06-01"));
        store.putReport(report(2, Status.RELEASED, "2026-06-10", PATIENT, ORG,
                new CorrectionContext(1, "corrected glucose value")));
        store.putReport(report(3, Status.HELD, "2026-06-12"));
        store.putReport(report(4, Status.WITHDRAWN, "2026-06-13"));
        store.putReport(report(5, Status.RELEASED, "2026-07-01"));
        store.putReport(report(6, Status.RELEASED, "2026-06-14", "other-patient", ORG, null));
        store.putReport(report(7, Status.RELEASED, "2026-06-14", PATIENT, OTHER_ORG, null));

        List<ReleasedReportView> views = service.listFor();
        // Revision 1 is superseded by the correction in revision 2, so only the current
        // release is shown and the patient is not offered a report that cannot be opened.
        assertEquals(1, views.size());
        assertEquals(2, views.get(0).revision());
        assertEquals("corrected glucose value", views.get(0).correctionReason());
        assertEquals(1, views.get(0).supersedesRevision());
        assertEquals("Clinic A", views.get(0).issuingOrganization());
        assertEquals("2026-06-10", views.get(0).effectiveDate());
        assertEquals("SUCCESS", audit.events().get(0).outcome());
        assertEquals(ReleasedReportService.PURPOSE, audit.events().get(0).purpose());
        assertEquals(ReleasedReportService.SCOPE, audit.events().get(0).scope());
        assertEquals(Instant.parse("2026-06-15T12:00:00Z"), audit.events().get(0).occurredAt());
        assertEquals("REPORT_LIST", audit.events().get(0).action());
        assertEquals(PATIENT, audit.events().get(0).resourceId());
        assertThrows(UnsupportedOperationException.class, () -> audit.events().clear());
    }

    @Test
    void listReturnsEmptyForPatientWithNoReports() {
        assertTrue(service.listFor().isEmpty());
        assertEquals(1, audit.events().size());
    }

    @Test
    void downloadRejectsInvalidInput() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.download(null, 1)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.download("bad id", 1)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.download(REPORT, 0)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.download(REPORT, -1)).status());
        assertTrue(audit.events().isEmpty());
    }

    @Test
    void downloadRequiresTenantContext() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
    }

    @Test
    void downloadDeniesAbsentUnverifiedAndInactiveLinks() {
        context(ORG, "unknown-subject");
        assertEquals(403, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        context(ORG, SUBJECT);
        link(PATIENT, false, true);
        assertEquals(403, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        link(PATIENT, true, false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        assertEquals(3, audit.events().size());
        assertTrue(audit.events().stream().allMatch(event -> "REPORT_DOWNLOAD".equals(event.action())));
    }

    @Test
    void downloadConcealsUnknownReport() {
        assertEquals(404, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        assertEquals("NOT_FOUND", audit.events().get(0).outcome());
    }

    @Test
    void downloadConcealsAnotherPatientsReport() {
        store.putReport(report(1, Status.RELEASED, "2026-06-01", "other-patient", ORG, null));
        assertEquals(404, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        assertEquals("DENIED", audit.events().get(0).outcome());
    }

    @Test
    void downloadRejectsWithdrawnReleaseEvenAfterPriorSuccess() {
        store.putReport(report(1, Status.RELEASED, "2026-06-01"));
        assertEquals(1, service.download(REPORT, 1).revision());
        assertTrue(store.withdraw(ORG, REPORT, 1));
        assertEquals(410, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        assertEquals("WITHDRAWN", audit.events().get(1).outcome());
        assertTrue(service.listFor().isEmpty());
    }

    @Test
    void withdrawnCorrectionDoesNotResurrectTheOriginal() {
        store.putReport(report(1, Status.RELEASED, "2026-06-01"));
        store.putReport(report(2, Status.RELEASED, "2026-06-02", PATIENT, ORG,
                new CorrectionContext(1, "corrected value")));
        assertEquals(409, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        assertTrue(store.withdraw(ORG, REPORT, 2));

        // The withdrawn correction is still the last effective correction: the superseded
        // original must not become the current release again.
        assertEquals(409, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        assertEquals(410, assertThrows(ApiException.class, () -> service.download(REPORT, 2)).status());
        assertTrue(service.listFor().isEmpty());
    }

    @Test
    void heldCorrectionDoesNotHideTheReleasedOriginal() {
        store.putReport(report(1, Status.RELEASED, "2026-06-01"));
        store.putReport(report(2, Status.HELD, "2026-06-02", PATIENT, ORG,
                new CorrectionContext(1, "pending correction")));
        assertEquals(1, service.download(REPORT, 1).revision());
        assertEquals(1, service.listFor().size());
    }

    @Test
    void supersessionIsScopedToTenantReportAndCorrectionTarget() {
        ReleasedReport first = report(1, Status.RELEASED, "2026-06-01");
        store.putReport(first);
        assertFalse(store.isSuperseded(ORG, first));

        store.putReport(report(2, Status.RELEASED, "2026-06-02", PATIENT, OTHER_ORG,
                new CorrectionContext(1, "other tenant")));
        assertFalse(store.isSuperseded(ORG, first));

        store.putReport(new ReleasedReport("report-other", 2, PATIENT, ORG, "Clinic A", "2026-06-02",
                "en", "doc", "sum", Status.RELEASED, new CorrectionContext(1, "other report")));
        assertFalse(store.isSuperseded(ORG, first));

        store.putReport(report(3, Status.RELEASED, "2026-06-03"));
        assertFalse(store.isSuperseded(ORG, first));
        assertFalse(store.isSuperseded(ORG, report(5, Status.RELEASED, "2026-06-05")));

        store.putReport(report(4, Status.RELEASED, "2026-06-04", PATIENT, ORG,
                new CorrectionContext(2, "corrects revision 2")));
        assertFalse(store.isSuperseded(ORG, first));

        store.putReport(report(5, Status.RELEASED, "2026-06-05", PATIENT, ORG,
                new CorrectionContext(1, "corrects revision 1")));
        assertTrue(store.isSuperseded(ORG, first));
    }

    @Test
    void downloadRejectsHeldRelease() {
        store.putReport(report(1, Status.HELD, "2026-06-01"));
        assertEquals(403, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        assertEquals("HELD", audit.events().get(0).outcome());
    }

    @Test
    void downloadRejectsFutureEffectiveRelease() {
        store.putReport(report(1, Status.RELEASED, "2026-07-01"));
        assertEquals(403, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        assertEquals("NOT_EFFECTIVE", audit.events().get(0).outcome());
    }

    @Test
    void downloadRejectsSupersededRevision() {
        store.putReport(report(1, Status.RELEASED, "2026-06-01"));
        store.putReport(report(2, Status.RELEASED, "2026-06-10"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.download(REPORT, 1)).status());
        assertEquals("STALE", audit.events().get(0).outcome());
        assertEquals(2, service.download(REPORT, 2).revision());
    }

    @Test
    void futureReleaseDoesNotMakeTheCurrentRevisionStale() {
        store.putReport(report(1, Status.RELEASED, "2026-06-01"));
        store.putReport(report(2, Status.RELEASED, "2026-07-01"));
        assertEquals(1, store.latestReleasedRevision(ORG, REPORT, LocalDate.parse("2026-06-15")).orElseThrow());
        assertEquals(1, service.download(REPORT, 1).revision());
        assertEquals(1, service.listFor().size());
    }

    @Test
    void downloadSucceedsForCurrentReleasedRevisionAndAudits() {
        store.putReport(report(1, Status.RELEASED, "2026-06-01", PATIENT, ORG,
                new CorrectionContext(0, "initial")));
        store.putReport(report(2, Status.HELD, "2026-06-02"));
        ReportDownload result = service.download(REPORT, 1);
        assertEquals(REPORT, result.reportId());
        assertEquals(1, result.revision());
        assertEquals("document-1", result.documentId());
        assertEquals("checksum-1", result.checksum());
        assertEquals("en", result.language());
        assertEquals(1, audit.events().size());
        assertEquals("SUCCESS", audit.events().get(0).outcome());
        assertEquals(SUBJECT, audit.events().get(0).subject());
        assertEquals(ORG, audit.events().get(0).organizationId());
        assertEquals(CORRELATION, audit.events().get(0).correlationId());
    }

    @Test
    void storeValidatesFixturesAndReportsMissingEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> store.putLink(null, SUBJECT, new PatientChartLink(PATIENT, true, true)));
        assertThrows(IllegalArgumentException.class,
                () -> store.putLink("bad-org", SUBJECT, new PatientChartLink(PATIENT, true, true)));
        assertThrows(IllegalArgumentException.class,
                () -> store.putLink(ORG, null, new PatientChartLink(PATIENT, true, true)));
        assertThrows(IllegalArgumentException.class,
                () -> store.putLink(ORG, "  ", new PatientChartLink(PATIENT, true, true)));
        assertThrows(IllegalArgumentException.class, () -> store.putLink(ORG, SUBJECT, null));

        assertThrows(IllegalArgumentException.class, () -> store.putReport(null));
        assertThrows(IllegalArgumentException.class,
                () -> store.putReport(report(1, Status.RELEASED, "2026-06-01", PATIENT, "bad-org", null)));
        assertThrows(IllegalArgumentException.class, () -> store.putReport(
                new ReleasedReport("bad id", 1, PATIENT, ORG, "Clinic A", "2026-06-01", "en",
                        "doc", "sum", Status.RELEASED, null)));
        assertThrows(IllegalArgumentException.class, () -> store.putReport(report(0, Status.RELEASED, "2026-06-01")));
        assertThrows(IllegalArgumentException.class, () -> store.putReport(report(1, Status.RELEASED, "not-a-date")));
        assertThrows(IllegalArgumentException.class, () -> store.putReport(
                new ReleasedReport("report-x", 1, PATIENT, ORG, "Clinic A", "2026-99-99", "en",
                        "doc", "sum", Status.RELEASED, null)));
        assertThrows(IllegalArgumentException.class, () -> store.putReport(report(1, null, "2026-06-01")));

        assertTrue(store.link(ORG, "unknown").isEmpty());
        assertTrue(store.find(ORG, REPORT, 1).isEmpty());
        assertTrue(store.reportsFor(ORG, "unknown").isEmpty());
        assertTrue(store.latestReleasedRevision(ORG, REPORT, LocalDate.parse("2026-06-15")).isEmpty());
        assertFalse(store.withdraw(ORG, REPORT, 1));
        assertTrue(audit.events().isEmpty());
    }
}
