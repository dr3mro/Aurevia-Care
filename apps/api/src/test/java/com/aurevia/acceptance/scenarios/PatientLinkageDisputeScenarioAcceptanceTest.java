package com.aurevia.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.enrollment.EnrollmentActions;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountView;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveAction;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveChangeRequest;
import com.aurevia.messaging.enrollment.InMemoryPatientAccessStore;
import com.aurevia.messaging.enrollment.PatientAccessActor;
import com.aurevia.messaging.enrollment.PatientAccessService;
import com.aurevia.messaging.reports.PatientChartLink;
import com.aurevia.messaging.reports.ReleasedReport;
import com.aurevia.messaging.reports.ReleasedReport.Status;
import com.aurevia.messaging.reports.ReleasedReportService;
import com.aurevia.messaging.reports.ReleasedReportService.InMemoryAuditSink;
import com.aurevia.messaging.reports.ReportReleaseStore;

import static com.aurevia.acceptance.scenarios.ScenarioSupport.CLINIC_ALPHA;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.NOW;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PATIENT;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PRIVACY_OFFICER;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.clear;

/**
 * Section 11 cross-cutting scenario: patient linkage dispute (IAM-05, MSG-07, PAT-02).
 *
 * <p>A privacy officer reviews a disputed patient's other authorized chart
 * associations while the affected link is suspended through the step-up
 * unlink workflow. Every subsequent protected read and document download
 * re-derives link status from the authoritative enrollment store: the disputed
 * chart stops serving the patient while the independently verified association
 * and the staff audit/disclosure history remain available. All identities are
 * synthetic.</p>
 */
class PatientLinkageDisputeScenarioAcceptanceTest {

    private static final String DISPUTED_CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
    private static final String OTHER_CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2";
    private static final String REPORT = "report-linkage-dispute";
    private static final String FACTS = "site-verified-facts";

    private static final String ACCOUNT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private InMemoryPatientAccessStore store;
    private PatientAccessService service;
    private PatientAccessActor patient;
    private PatientAccessActor privacy;
    private PatientAccount account;
    private ReportReleaseStore reportStore;
    private ReleasedReportService portal;

    @BeforeEach
    void setUp() {
        com.aurevia.acceptance.scenarios.ScenarioSupport.bind(CLINIC_ALPHA,
                ScenarioSupport.PATIENT_SUBJECT);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new InMemoryPatientAccessStore();
        service = new PatientAccessService(store, new EnrollmentActions(), clock);
        patient = PatientAccessActor.of(CLINIC_ALPHA, ScenarioSupport.PATIENT_SUBJECT, EnrollmentActions.PATIENT);
        privacy = PatientAccessActor.of(CLINIC_ALPHA, PRIVACY_OFFICER, EnrollmentActions.PRIVACY_AUDITOR);
        store.seedChart(CLINIC_ALPHA, DISPUTED_CHART);
        store.seedChart(CLINIC_ALPHA, OTHER_CHART);

        // Seed one verified account holding two independently verified chart
        // links; the enrollment service re-derives each link on every access.
        account = new PatientAccount(ACCOUNT, CLINIC_ALPHA, ScenarioSupport.PATIENT_SUBJECT,
                java.util.Set.of(DISPUTED_CHART, OTHER_CHART), FACTS,
                com.aurevia.messaging.enrollment.EnrollmentContract.AccountState.ACTIVE,
                0, null, NOW);
        store.putAccount(CLINIC_ALPHA, account);
        assertTrue(service.checkChartAccess(patient, account.id(), DISPUTED_CHART).allowed());
        assertTrue(service.checkChartAccess(patient, account.id(), OTHER_CHART).allowed());

        // The patient portal's link projection is derived from the authoritative
        // enrollment decision on every protected action, not cached at login.
        reportStore = mock(ReportReleaseStore.class);
        portal = new ReleasedReportService(reportStore, new InMemoryAuditSink(), clock);
        when(reportStore.link(CLINIC_ALPHA, ScenarioSupport.PATIENT_SUBJECT)).thenAnswer(invocation ->
                Optional.of(new PatientChartLink(PATIENT,
                        service.checkChartAccess(patient, account.id(), DISPUTED_CHART).allowed(), true)));
        when(reportStore.find(CLINIC_ALPHA, REPORT, 1)).thenReturn(Optional.of(report(Status.RELEASED)));
        when(reportStore.latestReleasedRevision(CLINIC_ALPHA, REPORT,
                NOW.atZone(ZoneOffset.UTC).toLocalDate())).thenReturn(OptionalInt.of(1));
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    @Test
    void privacyOfficerReviewsAssociationsAndSuspendedLinkStopsNewDownloads() {
        // IAM-05 / MSG-07: a privacy officer can still review the account's other
        // authorized associations and audit events after the disputed link is
        // suspended; the patient's access to that chart is re-derived immediately.
        AccountView reviewed = service.accountView(privacy, account.id());
        assertEquals(2, reviewed.account().linkedChartIds().size());

        // A new download is allowed while the link is still active.
        assertEquals(REPORT, portal.download(REPORT, 1).reportId());

        // The link is suspended through a renewed, purpose-bound step-up token.
        String reauth = service.issueReauth(patient, SensitiveAction.UNLINK_CHART.wire()).token();
        PatientAccount suspended = service.sensitiveChange(patient, new SensitiveChangeRequest(
                account.id(), SensitiveAction.UNLINK_CHART, reauth, DISPUTED_CHART));
        assertFalse(suspended.linkedChartIds().contains(DISPUTED_CHART));
        assertTrue(suspended.linkedChartIds().contains(OTHER_CHART));

        // In-flight reads and new downloads now refuse the disputed chart, while
        // the independent association and the staff audit history survive.
        assertFalse(service.checkChartAccess(patient, account.id(), DISPUTED_CHART).allowed());
        assertTrue(service.checkChartAccess(patient, account.id(), OTHER_CHART).allowed());
        ApiException denial = assertThrows(ApiException.class, () -> portal.download(REPORT, 1));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertFalse(service.accountView(privacy, account.id()).events().isEmpty());
        assertTrue(store.audit(CLINIC_ALPHA).stream().anyMatch(event ->
                event.eventType().name().contains("UNLINK")
                        || event.eventType().name().contains("ACCESS_DENIED")));
    }

    @Test
    void supportChannelResolvesADisputedLinkWithoutGrantingAccess() {
        // PAT-03: the disputed association has a privacy-safe review path; only
        // authorized staff may assign and resolve, and notifications stay generic.
        var opened = service.openCase(patient,
                new com.aurevia.messaging.enrollment.EnrollmentContract.OpenCaseRequest(
                        ScenarioSupport.PATIENT_SUBJECT,
                        com.aurevia.messaging.enrollment.EnrollmentContract.SupportKind.IDENTITY_DISPUTE,
                        "I cannot access my disputed chart"));
        PatientAccessActor support = PatientAccessActor.of(CLINIC_ALPHA,
                "syn-support-linkage", EnrollmentActions.SUPPORT_AGENT);
        var assigned = service.assignCase(support, opened.id(), "owner-dispute");
        var resolved = service.resolveCase(privacy, opened.id(), "link suspended after review");

        assertEquals("owner-dispute", assigned.assignee());
        assertEquals(com.aurevia.messaging.enrollment.EnrollmentContract.CaseState.RESOLVED,
                resolved.state());
        // Resolving a dispute never grants a new link and never carries clinical
        // detail into a notification.
        assertEquals(2, service.accountView(privacy, account.id()).account().linkedChartIds().size());
        assertTrue(store.notifications(CLINIC_ALPHA).stream().allMatch(notification ->
                notification.eventType().startsWith("support_case")));
    }

    private static ReleasedReport report(Status status) {
        return new ReleasedReport(REPORT, 1, PATIENT, CLINIC_ALPHA, "Synthetic clinic",
                "2026-11-20", "en", "document-1", "checksum-1", status, null);
    }
}
