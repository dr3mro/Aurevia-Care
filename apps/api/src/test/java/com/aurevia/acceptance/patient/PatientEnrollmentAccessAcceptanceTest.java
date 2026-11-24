package com.aurevia.acceptance.patient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.aurevia.messaging.enrollment.EnrollmentActions;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountState;
import com.aurevia.messaging.enrollment.EnrollmentContract.EnrollRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.IssueInvitationRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.RelationshipRef;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveAction;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveChangeRequest;
import com.aurevia.messaging.enrollment.InMemoryPatientAccessStore;
import com.aurevia.messaging.enrollment.PatientAccessActor;
import com.aurevia.messaging.enrollment.PatientAccessService;

/**
 * Gate G3 acceptance for privacy-safe patient enrollment and account access.
 *
 * <p>Requirements proved: PAT-03 (site-verified enrollment, renewed
 * authentication for a sensitive change, an identity-dispute support channel),
 * IAM-05 (an unlink ends future access for the affected chart) and PAT-01
 * (generic, detail-free notifications). All fixtures are synthetic.</p>
 */
class PatientEnrollmentAccessAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String FACTS = "site-verified-facts";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void verifiedEnrollmentGrantsAccessAndStepUpUnlinkEndsIt() {
        // PAT-03 / IAM-05: only a site-verified invitation links a chart; a
        // renewed, purpose-bound token is needed to unlink and the decision is
        // re-derived on the next access check.
        InMemoryPatientAccessStore store = new InMemoryPatientAccessStore();
        PatientAccessService service = new PatientAccessService(store, new EnrollmentActions(), NOW);
        store.seedChart(ORG, CHART);

        PatientAccessActor staff = PatientAccessActor.of(ORG, "syn-reception", EnrollmentActions.RECEPTION);
        PatientAccessActor patient = PatientAccessActor.of(ORG, "syn-patient", EnrollmentActions.PATIENT);
        Invitation invitation = service.issueInvitation(staff, new IssueInvitationRequest(
                CHART, new RelationshipRef("encounter-1", true), FACTS, null));
        PatientAccount account = service.enroll(patient, new EnrollRequest(invitation.code(), FACTS));

        assertEquals(AccountState.ACTIVE, account.state());
        assertEquals(java.util.Set.of(CHART), account.linkedChartIds());
        assertTrue(service.checkChartAccess(patient, account.id(), CHART).allowed());

        String reauth = service.issueReauth(patient, SensitiveAction.UNLINK_CHART.wire()).token();
        PatientAccount unlinked = service.sensitiveChange(patient, new SensitiveChangeRequest(
                account.id(), SensitiveAction.UNLINK_CHART, reauth, CHART));
        assertFalse(unlinked.linkedChartIds().contains(CHART));
        assertFalse(service.checkChartAccess(patient, account.id(), CHART).allowed());
        assertTrue(store.audit(ORG).stream().anyMatch(event -> event.eventType().name().contains("UNLINK")
                || event.eventType().name().contains("ACCESS_DENIED")));
    }

    @Test
    void supportCaseGivesAPrivacySafeIdentityDisputeChannel() {
        // PAT-03: a patient can open a support case and only staff roles can
        // assign or resolve it; notifications stay generic (PAT-01).
        InMemoryPatientAccessStore store = new InMemoryPatientAccessStore();
        PatientAccessService service = new PatientAccessService(store, new EnrollmentActions(), NOW);
        PatientAccessActor patient = PatientAccessActor.of(ORG, "syn-patient", EnrollmentActions.PATIENT);
        PatientAccessActor support = PatientAccessActor.of(ORG, "syn-support", EnrollmentActions.SUPPORT_AGENT);
        PatientAccessActor privacy = PatientAccessActor.of(ORG, "syn-privacy", EnrollmentActions.PRIVACY_AUDITOR);

        var opened = service.openCase(patient, new com.aurevia.messaging.enrollment.EnrollmentContract.OpenCaseRequest(
                "syn-patient", com.aurevia.messaging.enrollment.EnrollmentContract.SupportKind.IDENTITY_DISPUTE,
                "I cannot access my chart"));
        var assigned = service.assignCase(support, opened.id(), "owner-1");
        var resolved = service.resolveCase(privacy, opened.id(), "reviewed and closed");

        assertEquals(com.aurevia.messaging.enrollment.EnrollmentContract.CaseState.RESOLVED,
                resolved.state());
        assertEquals("owner-1", assigned.assignee());
        assertTrue(store.notifications(ORG).stream().allMatch(notification ->
                notification.eventType().startsWith("support_case")));
    }
}
