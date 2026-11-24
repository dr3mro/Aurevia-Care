package com.aurevia.it.patientidentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.patient.identity.ChartLink;
import com.aurevia.patient.identity.IdentityActionPolicy;
import com.aurevia.patient.identity.IdentityProofingController;
import com.aurevia.patient.identity.IdentityProofingRepository;
import com.aurevia.patient.identity.IdentityProofingService;
import com.aurevia.patient.identity.InvitationIssue;
import com.aurevia.patient.identity.InvitationRecord;
import com.aurevia.patient.identity.PatientAccount;
import com.aurevia.tenant.TenantContext;

/** S-G0-07 coverage evidence (IAM-05): command failure branches and the HTTP/value adapters. */
@ExtendWith(MockitoExtension.class)
class IdentityProofingCoverageTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String STAFF = "registrar-a";

    @Mock
    private IdentityProofingRepository repository;
    @Mock
    private IdentityActionPolicy policy;
    @Mock
    private IdentityProofingService mockedService;

    private IdentityProofingService service;
    private IdentityProofingController controller;

    @BeforeEach
    void setUp() {
        service = new IdentityProofingService(repository, policy,
                new TransactionTemplate(new PassthroughTransactionManager()));
        controller = new IdentityProofingController(mockedService);
        TenantContext.set(new TenantContext(ORG, STAFF, "PATIENT_IDENTITY", "corr-cov"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static final class PassthroughTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no durable state
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no durable state
        }
    }

    private void deny(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }

    @Test
    void validationRejectsMalformedFieldsAcrossCommands() {
        deny(422, "INVALID_NAME", () -> service.enroll("patient.one", "!"));
        deny(422, "INVALID_SUBJECT", () -> service.issueInvitation("bad subject", CHART, "LINK", 600));
        deny(422, "INVALID_CHART_ID", () -> service.issueInvitation("patient.one", "not-a-uuid", "LINK", 600));
        deny(422, "INVALID_TOKEN", () -> service.linkWithInvitation("!"));
        deny(422, "INVALID_SUBJECT", () -> service.unlink("bad subject", CHART, "reason"));
        deny(422, "INVALID_CHART_ID", () -> service.unlink("patient.one", "bad", "reason"));
        deny(422, "INVALID_REASON", () -> service.unlink("patient.one", CHART, "!"));
        deny(422, "INVALID_SUBJECT", () -> service.reverify("bad subject", CHART, "reason"));
        deny(422, "INVALID_CHART_ID", () -> service.reverify("patient.one", "bad", "reason"));
        deny(422, "INVALID_REASON", () -> service.reverify("patient.one", CHART, "!"));
        deny(422, "INVALID_SUBJECT", () -> service.hasAccess("bad subject", CHART));
        deny(422, "INVALID_CHART_ID", () -> service.hasAccess("patient.one", "bad"));
        deny(422, "INVALID_SUBJECT", () -> service.links("bad subject"));
    }

    @Test
    void nullAndOutOfRangeBoundsAreRejected() {
        deny(422, "INVALID_SUBJECT", () -> service.enroll(null, "Patient One"));
        deny(422, "INVALID_SUBJECT", () -> service.issueInvitation(null, CHART, "LINK", 600));
        deny(422, "INVALID_PURPOSE", () -> service.issueInvitation("patient.one", CHART, null, 600));
        deny(422, "INVALID_TTL", () -> service.issueInvitation("patient.one", CHART, "LINK", 90_000));
        deny(422, "INVALID_TOKEN", () -> service.linkWithInvitation(null));
        deny(422, "INVALID_SUBJECT", () -> service.hasAccess(null, CHART));
        deny(422, "INVALID_SUBJECT", () -> service.links(null));
    }

    @Test
    void selfReadWithoutVerifiedAccountIsDeniedAndAudited() {
        TenantContext.set(new TenantContext(ORG, "patient.one", "PATIENT_IDENTITY", "corr-selfread"));
        when(repository.accountExists("patient.one")).thenReturn(false);
        deny(403, "FORBIDDEN", () -> service.hasAccess("patient.one", CHART));
        deny(403, "FORBIDDEN", () -> service.links("patient.one"));
        verify(repository, org.mockito.Mockito.times(2))
                .appendAudit(any(), eq("LINK_READ"), anyString(), eq("DENIED"));
    }

    @Test
    void privilegedTenantAdminActionsAreAllowed() {
        IdentityActionPolicy real = new IdentityActionPolicy();
        assertTrue(real.allows(IdentityActionPolicy.LINK_REVERIFY, "tenant_admin"));
        assertTrue(real.allows(IdentityActionPolicy.INVITATION_ISSUE, "tenant_admin"));
        assertFalse(real.allows(IdentityActionPolicy.ACCOUNT_ENROLL, "privacy_auditor"));
        assertFalse(real.allows(IdentityActionPolicy.LINK_READ, "reception"));
    }

    @Test
    void staffDenialIsAuditedForEachCommand() {
        when(repository.hasActiveMembership(STAFF)).thenReturn(false);
        deny(403, "FORBIDDEN", () -> service.issueInvitation("patient.one", CHART, "LINK", 600));
        verify(repository).appendAudit(any(), eq("INVITATION_ISSUE"), eq(CHART), eq("DENIED"));

        when(repository.hasActiveMembership(STAFF)).thenReturn(true);
        when(repository.activeRoles(STAFF)).thenReturn(List.of("clinician"));
        when(policy.allows(IdentityActionPolicy.LINK_UNLINK, "clinician")).thenReturn(false);
        deny(403, "FORBIDDEN", () -> service.unlink("patient.one", CHART, "reason"));
        verify(repository).appendAudit(any(), eq("CHART_UNLINK"), eq(CHART), eq("DENIED"));

        when(policy.allows(IdentityActionPolicy.LINK_REVERIFY, "clinician")).thenReturn(false);
        deny(403, "FORBIDDEN", () -> service.reverify("patient.one", CHART, "reason"));
        verify(repository).appendAudit(any(), eq("LINK_REVERIFY"), eq(CHART), eq("DENIED"));
    }

    @Test
    void revertDenialAndMissingEntitiesAreAudited() {
        when(repository.hasActiveMembership(STAFF)).thenReturn(true);
        when(repository.activeRoles(STAFF)).thenReturn(List.of("tenant_admin"));
        when(policy.allows(IdentityActionPolicy.LINK_REVERIFY, "tenant_admin")).thenReturn(true);

        when(repository.accountExists("patient.one")).thenReturn(false);
        deny(404, "ACCOUNT_NOT_FOUND", () -> service.reverify("patient.one", CHART, "reason"));
        when(repository.accountExists("patient.one")).thenReturn(true);
        when(repository.chartExists(CHART)).thenReturn(false);
        deny(404, "CHART_NOT_FOUND", () -> service.reverify("patient.one", CHART, "reason"));
        when(repository.chartExists(CHART)).thenReturn(true);
        when(repository.currentLink("patient.one", CHART)).thenReturn(Optional.empty());
        deny(404, "LINK_NOT_FOUND", () -> service.reverify("patient.one", CHART, "reason"));
        verify(repository, org.mockito.Mockito.times(3))
                .appendAudit(any(), eq("LINK_REVERIFY"), eq(CHART), eq("NOT_FOUND"));
    }

    @Test
    void selfUnlinkWithoutVerifiedAccountIsDenied() {
        TenantContext.set(new TenantContext(ORG, "patient.one", "PATIENT_IDENTITY", "corr-self"));
        when(repository.accountExists("patient.one")).thenReturn(false);
        deny(403, "FORBIDDEN", () -> service.unlink("patient.one", CHART, "reason"));
        verify(repository).appendAudit(any(), eq("CHART_UNLINK"), eq(CHART), eq("DENIED"));
    }

    @Test
    void raceLostConsumptionIsConflict() {
        TenantContext.set(new TenantContext(ORG, "patient.one", "PATIENT_IDENTITY", "corr-race"));
        when(repository.accountExists("patient.one")).thenReturn(true);
        InvitationRecord invitation = new InvitationRecord("inv-race", "patient.one", CHART, "LINK",
                "2026-11-19T12:00:00Z", false);
        when(repository.findInvitation(anyString())).thenReturn(Optional.of(invitation));
        when(repository.consumeInvitation("inv-race")).thenReturn(false);
        deny(409, "INVITATION_UNAVAILABLE", () -> service.linkWithInvitation("raw-token-value"));
        verify(repository).appendAudit(any(), eq("CHART_LINK"), eq("ME"), eq("CONFLICT"));
    }

    @Test
    void staffReadWithoutLinkReadPermissionIsDenied() {
        when(repository.hasActiveMembership(STAFF)).thenReturn(true);
        when(repository.activeRoles(STAFF)).thenReturn(List.of("reception"));
        when(policy.allows(IdentityActionPolicy.LINK_READ, "reception")).thenReturn(false);
        deny(403, "FORBIDDEN", () -> service.links("patient.two"));
        deny(403, "FORBIDDEN", () -> service.hasAccess("patient.two", CHART));
    }

    @Test
    void controllerBindsEveryEndpointToTheService() {
        ResponseEntity<PatientAccount> enrolled = controller.enroll(
                new IdentityProofingController.EnrollRequest("patient.one", "Patient One"));
        assertEquals(HttpStatus.CREATED, enrolled.getStatusCode());
        verify(mockedService).enroll("patient.one", "Patient One");

        ResponseEntity<InvitationIssue> issued = controller.issue(
                new IdentityProofingController.InvitationRequest("patient.one", CHART, "LINK", 600));
        assertEquals(HttpStatus.CREATED, issued.getStatusCode());
        verify(mockedService).issueInvitation("patient.one", CHART, "LINK", 600);

        controller.claim(new IdentityProofingController.ClaimRequest("raw-token"));
        verify(mockedService).linkWithInvitation("raw-token");

        controller.unlink(new IdentityProofingController.UnlinkRequest("patient.one", CHART, "reason"));
        verify(mockedService).unlink("patient.one", CHART, "reason");

        controller.reverify(new IdentityProofingController.ReverifyRequest("patient.one", CHART, "reason"));
        verify(mockedService).reverify("patient.one", CHART, "reason");

        when(mockedService.hasAccess("patient.one", CHART)).thenReturn(true);
        assertTrue(controller.hasAccess("patient.one", CHART));

        when(mockedService.links("patient.one")).thenReturn(List.of());
        assertTrue(controller.links("patient.one").isEmpty());
    }

    @Test
    void valueRecordsExposePartsAndValueSemantics() {
        PatientAccount account = new PatientAccount("acct", "patient.one", "Patient One");
        assertEquals("acct", account.id());
        assertEquals("patient.one", account.subject());
        assertEquals("Patient One", account.displayName());
        assertEquals(account, new PatientAccount("acct", "patient.one", "Patient One"));
        assertEquals(account.hashCode(), new PatientAccount("acct", "patient.one", "Patient One").hashCode());
        assertTrue(account.toString().contains("patient.one"));
        assertNotEquals(account, new PatientAccount("other", "patient.one", "Patient One"));

        InvitationRecord invitation = new InvitationRecord("inv", "patient.one", CHART, "LINK", "exp", false);
        assertEquals("inv", invitation.id());
        assertEquals("patient.one", invitation.accountSubject());
        assertEquals(CHART, invitation.chartId());
        assertEquals("LINK", invitation.purpose());
        assertEquals("exp", invitation.expiresAt());
        assertFalse(invitation.consumed());
        assertEquals(invitation, new InvitationRecord("inv", "patient.one", CHART, "LINK", "exp", false));
        assertEquals(invitation.hashCode(), new InvitationRecord("inv", "patient.one", CHART, "LINK", "exp", false).hashCode());
        assertTrue(invitation.toString().contains("inv"));

        InvitationIssue issue = new InvitationIssue("patient.one", CHART, "LINK", "raw", "exp");
        assertEquals("patient.one", issue.subject());
        assertEquals(CHART, issue.chartId());
        assertEquals("LINK", issue.purpose());
        assertEquals("raw", issue.token());
        assertEquals("exp", issue.expiresAt());
        assertEquals(issue, new InvitationIssue("patient.one", CHART, "LINK", "raw", "exp"));
        assertEquals(issue.hashCode(), new InvitationIssue("patient.one", CHART, "LINK", "raw", "exp").hashCode());
        assertTrue(issue.toString().contains("raw"));

        ChartLink link = new ChartLink("link", "patient.one", CHART, "linked", "INVITATION", null, "at");
        assertEquals("link", link.id());
        assertEquals("patient.one", link.accountSubject());
        assertEquals(CHART, link.chartId());
        assertEquals("linked", link.event());
        assertEquals("INVITATION", link.method());
        assertEquals(null, link.reason());
        assertEquals("at", link.occurredAt());
        assertEquals(link, new ChartLink("link", "patient.one", CHART, "linked", "INVITATION", null, "at"));
        assertEquals(link.hashCode(), new ChartLink("link", "patient.one", CHART, "linked", "INVITATION", null, "at").hashCode());
        assertTrue(link.toString().contains("linked"));
    }

    @Test
    void requestRecordsExposePartsAndValueSemantics() {
        var enroll = new IdentityProofingController.EnrollRequest("patient.one", "Patient One");
        assertEquals("patient.one", enroll.subject());
        assertEquals("Patient One", enroll.displayName());
        assertEquals(enroll, new IdentityProofingController.EnrollRequest("patient.one", "Patient One"));
        assertEquals(enroll.hashCode(), new IdentityProofingController.EnrollRequest("patient.one", "Patient One").hashCode());
        assertTrue(enroll.toString().contains("patient.one"));

        var invitation = new IdentityProofingController.InvitationRequest("patient.one", CHART, "LINK", 600);
        assertEquals("patient.one", invitation.subject());
        assertEquals(CHART, invitation.chartId());
        assertEquals("LINK", invitation.purpose());
        assertEquals(600, invitation.ttlSeconds());
        assertEquals(invitation, new IdentityProofingController.InvitationRequest("patient.one", CHART, "LINK", 600));
        assertEquals(invitation.hashCode(),
                new IdentityProofingController.InvitationRequest("patient.one", CHART, "LINK", 600).hashCode());
        assertTrue(invitation.toString().contains("LINK"));

        var claim = new IdentityProofingController.ClaimRequest("raw");
        assertEquals("raw", claim.token());
        assertEquals(claim, new IdentityProofingController.ClaimRequest("raw"));
        assertEquals(claim.hashCode(), new IdentityProofingController.ClaimRequest("raw").hashCode());
        assertTrue(claim.toString().contains("raw"));

        var unlink = new IdentityProofingController.UnlinkRequest("patient.one", CHART, "reason");
        assertEquals("patient.one", unlink.subject());
        assertEquals(CHART, unlink.chartId());
        assertEquals("reason", unlink.reason());
        assertEquals(unlink, new IdentityProofingController.UnlinkRequest("patient.one", CHART, "reason"));
        assertEquals(unlink.hashCode(),
                new IdentityProofingController.UnlinkRequest("patient.one", CHART, "reason").hashCode());
        assertTrue(unlink.toString().contains("reason"));

        var reverify = new IdentityProofingController.ReverifyRequest("patient.one", CHART, "reason");
        assertEquals("patient.one", reverify.subject());
        assertEquals(CHART, reverify.chartId());
        assertEquals("reason", reverify.reason());
        assertEquals(reverify, new IdentityProofingController.ReverifyRequest("patient.one", CHART, "reason"));
        assertEquals(reverify.hashCode(),
                new IdentityProofingController.ReverifyRequest("patient.one", CHART, "reason").hashCode());
        assertTrue(reverify.toString().contains("reason"));
    }
}
