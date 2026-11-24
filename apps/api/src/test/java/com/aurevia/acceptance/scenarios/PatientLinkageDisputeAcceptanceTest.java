package com.aurevia.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.enrollment.EnrollmentActions;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountState;
import com.aurevia.messaging.enrollment.EnrollmentContract.CaseState;
import com.aurevia.messaging.enrollment.EnrollmentContract.EnrollRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.IssueInvitationRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.OpenCaseRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.RelationshipRef;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveAction;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveChangeRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportKind;
import com.aurevia.messaging.enrollment.InMemoryPatientAccessStore;
import com.aurevia.messaging.enrollment.PatientAccessActor;
import com.aurevia.messaging.enrollment.PatientAccessService;
import com.aurevia.messaging.reports.PatientChartLink;
import com.aurevia.messaging.reports.ReleasedReport;
import com.aurevia.messaging.reports.ReleasedReport.Status;
import com.aurevia.messaging.reports.ReleasedReportService;
import com.aurevia.messaging.reports.ReleasedReportService.InMemoryAuditSink;
import com.aurevia.messaging.reports.ReportReleaseStore;

/**
 * Cross-cutting acceptance for a disputed patient-chart association.
 *
 * <p>Requirement PAT-02: a patient sees a chart only through a live verified
 * link. Spec section 11 "Patient linkage dispute": the privacy officer reviews
 * the dispute, the affected link is ended through renewed authentication, every
 * later read or download rechecks link status, and the clinical chart and
 * disclosure history stay available to authorized staff. The closest existing
 * primitives are the identity-dispute support case and {@code UNLINK_CHART};
 * both clinics use the same local chart identifier to prove tenant isolation.
 * All fixtures are synthetic.</p>
 */
class PatientLinkageDisputeAcceptanceTest extends FirstSliceFixture {

    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String REPORT = "report-disputed";
    private static final String FACTS = "site-verified-facts";
    private static final Clock ACCESS_CLOCK =
            Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC);

    private final PatientAccessActor reception =
            PatientAccessActor.of(CLINIC_A, "syn-reception", EnrollmentActions.RECEPTION);
    private final PatientAccessActor patientActor =
            PatientAccessActor.of(CLINIC_A, "syn-disputed-patient", EnrollmentActions.PATIENT);
    private final PatientAccessActor peerPatient =
            PatientAccessActor.of(CLINIC_B, "syn-disputed-patient", EnrollmentActions.PATIENT);
    private final PatientAccessActor support =
            PatientAccessActor.of(CLINIC_A, "syn-support", EnrollmentActions.SUPPORT_AGENT);
    private final PatientAccessActor privacy =
            PatientAccessActor.of(CLINIC_A, "syn-privacy", EnrollmentActions.PRIVACY_AUDITOR);

    private InMemoryPatientAccessStore accessStore;
    private PatientAccessService access;
    private ReportReleaseStore reportStore;
    private InMemoryAuditSink reportAudit;
    private ReleasedReportService patientReports;
    private PatientAccount account;

    /** Enroll a synthetic patient in both clinics against the same local chart id. */
    @BeforeEach
    void setUpDispute() {
        accessStore = new InMemoryPatientAccessStore();
        access = new PatientAccessService(accessStore, new EnrollmentActions(), ACCESS_CLOCK);
        accessStore.seedChart(CLINIC_A, CHART);
        accessStore.seedChart(CLINIC_B, CHART);

        reportStore = mock(ReportReleaseStore.class);
        reportAudit = new InMemoryAuditSink();
        patientReports = new ReleasedReportService(reportStore, reportAudit, clock());
        when(reportStore.link(CLINIC_A, "syn-disputed-patient")).thenReturn(
                Optional.of(new PatientChartLink(CHART, true, true)));
        when(reportStore.reportsFor(CLINIC_A, CHART)).thenReturn(List.of(released()));
        when(reportStore.find(CLINIC_A, REPORT, 1)).thenReturn(Optional.of(released()));
        when(reportStore.latestReleasedRevision(CLINIC_A, REPORT, NOW.atZone(ZoneOffset.UTC).toLocalDate()))
                .thenReturn(OptionalInt.of(1));

        Invitation invitation = access.issueInvitation(reception, new IssueInvitationRequest(
                CHART, new RelationshipRef("encounter-dispute", true), FACTS, null));
        account = access.enroll(patientActor, new EnrollRequest(invitation.code(), FACTS));
        assertEquals(AccountState.ACTIVE, account.state());
        bind(CLINIC_A, "syn-disputed-patient");
    }

    @Test
    void disputeReviewEndsTheLinkAndEveryLaterPatientReadRechecksIt() {
        // Spec 11 / PAT-02: a dispute is reviewed by support and the privacy
        // officer; ending the link is a renewed, purpose-bound action; a read
        // already in flight and a fresh download both fail closed afterwards,
        // while the audit trail keeps the disclosure and unlink facts.
        assertTrue(access.checkChartAccess(patientActor, account.id(), CHART).allowed());
        assertEquals(1, patientReports.listFor().size());
        assertEquals(REPORT, patientReports.download(REPORT, 1).reportId());

        var opened = access.openCase(patientActor, new OpenCaseRequest("syn-disputed-patient",
                SupportKind.IDENTITY_DISPUTE, "synthetic linkage dispute"));
        var assigned = access.assignCase(support, opened.id(), "owner-dispute");
        var resolved = access.resolveCase(privacy, opened.id(), "synthetic reviewed and suspended");
        assertEquals(CaseState.RESOLVED, resolved.state());
        assertEquals("owner-dispute", assigned.assignee());

        String reauth = access.issueReauth(patientActor, SensitiveAction.UNLINK_CHART.wire()).token();
        PatientAccount unlinked = access.sensitiveChange(patientActor, new SensitiveChangeRequest(
                account.id(), SensitiveAction.UNLINK_CHART, reauth, CHART));
        assertFalse(unlinked.linkedChartIds().contains(CHART));

        when(reportStore.link(CLINIC_A, "syn-disputed-patient")).thenReturn(Optional.empty());
        ApiException inFlightRead = assertThrows(ApiException.class, () -> patientReports.listFor());
        assertEquals(403, inFlightRead.status());
        assertEquals("FORBIDDEN", inFlightRead.code());
        ApiException newDownload = assertThrows(ApiException.class, () -> patientReports.download(REPORT, 1));
        assertEquals(403, newDownload.status());
        assertEquals("FORBIDDEN", newDownload.code());

        assertTrue(accessStore.audit(CLINIC_A).stream().anyMatch(event ->
                event.eventType().name().contains("UNLINK")));
        assertTrue(reportAudit.events().stream().anyMatch(event -> event.action().equals(
                com.aurevia.messaging.reports.ReleasedReportService.ACTION_LIST)));
    }

    @Test
    void staleReauthAndPeerTenantCannotUseTheDisputedChart() {
        // Spec 11 / IAM-01: the step-up token is single use, and the same local
        // chart id in another clinic does not grant a peer-tenant patient access
        // or expose the disputed clinic's disclosure history.
        String reauth = access.issueReauth(patientActor, SensitiveAction.UNLINK_CHART.wire()).token();
        access.sensitiveChange(patientActor, new SensitiveChangeRequest(account.id(),
                SensitiveAction.UNLINK_CHART, reauth, CHART));
        ApiException reused = assertThrows(ApiException.class, () -> access.sensitiveChange(patientActor,
                new SensitiveChangeRequest(account.id(), SensitiveAction.UNLINK_CHART, reauth, CHART)));
        assertEquals(401, reused.status());
        assertEquals("STALE_REAUTH", reused.code());

        bind(CLINIC_B, "syn-disputed-patient");
        ApiException peerRead = assertThrows(ApiException.class,
                () -> access.checkChartAccess(peerPatient, account.id(), CHART));
        assertEquals(404, peerRead.status());
        assertTrue(accessStore.audit(CLINIC_B).stream()
                .allMatch(event -> CLINIC_B.equals(event.organizationId())),
                "no clinic A audit fact may leak into the peer tenant's trail");
        assertTrue(accessStore.audit(CLINIC_A).stream()
                .anyMatch(event -> event.eventType().name().contains("UNLINK")
                        && CLINIC_A.equals(event.organizationId())));
    }

    private static ReleasedReport released() {
        return new ReleasedReport(REPORT, 1, CHART, CLINIC_A, "Synthetic clinic", "2026-11-20",
                "en", "document-1", "checksum-1", Status.RELEASED, null);
    }
}
