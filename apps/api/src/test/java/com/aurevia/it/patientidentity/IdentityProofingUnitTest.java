package com.aurevia.it.patientidentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.patient.identity.ChartLink;
import com.aurevia.patient.identity.IdentityActionPolicy;
import com.aurevia.patient.identity.IdentityProofingRepository;
import com.aurevia.patient.identity.IdentityProofingService;
import com.aurevia.patient.identity.InvitationIssue;
import com.aurevia.patient.identity.InvitationRecord;
import com.aurevia.patient.identity.PatientAccount;
import com.aurevia.tenant.TenantContext;

/** S-G0-07 red-first unit evidence (IAM-05): domain commands without a database. */
@ExtendWith(MockitoExtension.class)
class IdentityProofingUnitTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String STAFF = "registrar-a";

    @Mock
    private IdentityProofingRepository repository;
    @Mock
    private IdentityActionPolicy policy;

    private IdentityProofingService service;

    @BeforeEach
    void setUp() {
        service = new IdentityProofingService(repository, policy,
                new TransactionTemplate(new PassthroughTransactionManager()));
        TenantContext.set(new TenantContext(ORG, STAFF, "PATIENT_IDENTITY", "corr-1"));
    }

    /** Runs callbacks directly so DB-less tests exercise service logic without mocks. */
    private static final class PassthroughTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no durable state in unit tests
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no durable state in unit tests
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void staffAllowed(String action) {
        when(repository.hasActiveMembership(STAFF)).thenReturn(true);
        when(repository.activeRoles(STAFF)).thenReturn(List.of("tenant_admin"));
        when(policy.allows(action, "tenant_admin")).thenReturn(true);
    }

    private ApiException expect(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
        return failure;
    }

    @Test
    void newTokenIsBoundedAndDistinct() {
        String first = IdentityProofingService.newToken();
        String second = IdentityProofingService.newToken();
        assertTrue(first.length() >= 32 && first.length() <= 64);
        assertFalse(first.equals(second));
    }

    @Test
    void policySeparatesEnrollmentFromLinkAdministration() {
        IdentityActionPolicy real = new IdentityActionPolicy();
        assertTrue(real.allows(IdentityActionPolicy.ACCOUNT_ENROLL, "tenant_admin"));
        assertTrue(real.allows(IdentityActionPolicy.ACCOUNT_ENROLL, "reception"));
        assertFalse(real.allows(IdentityActionPolicy.LINK_REVERIFY, "reception"));
        assertTrue(real.allows(IdentityActionPolicy.LINK_UNLINK, "privacy_auditor"));
        assertFalse(real.allows(IdentityActionPolicy.INVITATION_ISSUE, "clinician"));
        assertFalse(real.allows("UNKNOWN_ACTION", "tenant_admin"));
    }

    @Test
    void enrollCreatesVerifiedAccountAndAuditsSuccess() {
        staffAllowed(IdentityActionPolicy.ACCOUNT_ENROLL);
        when(repository.accountExists("patient.one")).thenReturn(false);
        when(repository.findAccount("patient.one"))
                .thenReturn(Optional.of(new PatientAccount("acct-1", "patient.one", "Patient One")));

        PatientAccount account = service.enroll("patient.one", "Patient One");

        assertEquals("patient.one", account.subject());
        verify(repository).insertAccount(eq(ORG), anyString(), eq("patient.one"), eq("Patient One"));
        verify(repository).appendAudit(any(), eq("ACCOUNT_ENROLL"), eq("patient.one"), eq("SUCCESS"));
    }

    @Test
    void enrollDeniesMissingMembershipAndAuditsDenial() {
        when(repository.hasActiveMembership(STAFF)).thenReturn(false);
        expect(403, "FORBIDDEN", () -> service.enroll("patient.one", "Patient One"));
        verify(repository).appendAudit(any(), eq("ACCOUNT_ENROLL"), eq("patient.one"), eq("DENIED"));
        verify(repository, never()).insertAccount(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void enrollDeniesRoleWithoutPermission() {
        when(repository.hasActiveMembership(STAFF)).thenReturn(true);
        when(repository.activeRoles(STAFF)).thenReturn(List.of("reception"));
        when(policy.allows(IdentityActionPolicy.ACCOUNT_ENROLL, "reception")).thenReturn(false);
        expect(403, "FORBIDDEN", () -> service.enroll("patient.one", "Patient One"));
    }

    @Test
    void enrollRejectsInvalidSubjectBeforeAnyWrite() {
        expect(422, "INVALID_SUBJECT", () -> service.enroll("bad subject!", "Patient One"));
        verify(repository, never()).hasActiveMembership(anyString());
    }

    @Test
    void enrollRejectsDuplicateAccountWithConflict() {
        staffAllowed(IdentityActionPolicy.ACCOUNT_ENROLL);
        when(repository.accountExists("patient.one")).thenReturn(true);
        expect(409, "ACCOUNT_EXISTS", () -> service.enroll("patient.one", "Patient One"));
        verify(repository).appendAudit(any(), eq("ACCOUNT_ENROLL"), eq("patient.one"), eq("CONFLICT"));
    }

    @Test
    void issueInvitationReturnsRawTokenAndStoresOnlyHash() {
        staffAllowed(IdentityActionPolicy.INVITATION_ISSUE);
        when(repository.accountExists("patient.one")).thenReturn(true);
        when(repository.chartExists(CHART)).thenReturn(true);

        InvitationIssue issue = service.issueInvitation("patient.one", CHART, "LINK", 600L);

        assertEquals("patient.one", issue.subject());
        assertEquals(CHART, issue.chartId());
        assertNotNull(issue.token());
        ArgumentCaptor<String> storedToken = ArgumentCaptor.forClass(String.class);
        verify(repository).insertInvitation(eq(ORG), anyString(), eq("patient.one"), eq(CHART),
                eq("LINK"), storedToken.capture(), eq(600L));
        assertEquals(issue.token(), storedToken.getValue(), "DB hashes the raw token; Java passes it through");
        verify(repository).appendAudit(any(), eq("INVITATION_ISSUE"), eq(CHART), eq("SUCCESS"));
    }

    @Test
    void issueInvitationRejectsUnknownAccountAndChart() {
        staffAllowed(IdentityActionPolicy.INVITATION_ISSUE);
        when(repository.accountExists("patient.one")).thenReturn(false);
        expect(404, "ACCOUNT_NOT_FOUND",
                () -> service.issueInvitation("patient.one", CHART, "LINK", 600L));
        when(repository.accountExists("patient.one")).thenReturn(true);
        when(repository.chartExists(CHART)).thenReturn(false);
        expect(404, "CHART_NOT_FOUND",
                () -> service.issueInvitation("patient.one", CHART, "LINK", 600L));
    }

    @Test
    void issueInvitationRejectsInvalidPurposeAndTtl() {
        expect(422, "INVALID_PURPOSE",
                () -> service.issueInvitation("patient.one", CHART, "ANYTHING", 600L));
        expect(422, "INVALID_TTL",
                () -> service.issueInvitation("patient.one", CHART, "LINK", 0L));
    }

    @Test
    void claimCreatesLinkAndMarksInvitationConsumed() {
        TenantContext.set(new TenantContext(ORG, "patient.one", "PATIENT_IDENTITY", "corr-2"));
        when(repository.accountExists("patient.one")).thenReturn(true);
        InvitationRecord invitation = new InvitationRecord("inv-1", "patient.one", CHART, "LINK",
                "2026-11-19T12:00:00Z", false);
        when(repository.findInvitation(anyString())).thenReturn(Optional.of(invitation));
        when(repository.consumeInvitation("inv-1")).thenReturn(true);
        when(repository.insertLink(eq(ORG), anyString(), eq("patient.one"), eq(CHART),
                eq("linked"), eq("INVITATION"), eq(null))).thenReturn(1);
        when(repository.currentLink("patient.one", CHART)).thenReturn(Optional.of(
                new ChartLink("link-1", "patient.one", CHART, "linked", "INVITATION", null, "2026-11-19T11:00:00Z")));

        ChartLink link = service.linkWithInvitation("raw-token-value");

        assertEquals("linked", link.event());
        verify(repository).consumeInvitation("inv-1");
        verify(repository).appendAudit(any(), eq("CHART_LINK"), eq(CHART), eq("SUCCESS"));
    }

    @Test
    void claimRejectsUnknownTokenWrongAccountAndConsumedInvitation() {
        TenantContext.set(new TenantContext(ORG, "patient.one", "PATIENT_IDENTITY", "corr-2"));
        when(repository.accountExists("patient.one")).thenReturn(true);
        when(repository.findInvitation(anyString())).thenReturn(Optional.empty());
        expect(404, "INVITATION_NOT_FOUND", () -> service.linkWithInvitation("raw-token-value"));

        InvitationRecord other = new InvitationRecord("inv-2", "patient.two", CHART, "LINK",
                "2026-11-19T12:00:00Z", false);
        when(repository.findInvitation(anyString())).thenReturn(Optional.of(other));
        expect(403, "FORBIDDEN", () -> service.linkWithInvitation("raw-token-value"));

        InvitationRecord used = new InvitationRecord("inv-3", "patient.one", CHART, "LINK",
                "2026-11-19T12:00:00Z", true);
        when(repository.findInvitation(anyString())).thenReturn(Optional.of(used));
        expect(409, "INVITATION_UNAVAILABLE", () -> service.linkWithInvitation("raw-token-value"));
        verify(repository, never()).consumeInvitation("inv-3");
    }

    @Test
    void claimRejectsCallerWithoutVerifiedAccount() {
        TenantContext.set(new TenantContext(ORG, "stranger", "PATIENT_IDENTITY", "corr-3"));
        when(repository.accountExists("stranger")).thenReturn(false);
        expect(403, "FORBIDDEN", () -> service.linkWithInvitation("raw-token-value"));
        verify(repository, never()).consumeInvitation(anyString());
    }

    @Test
    void patientUnlinkAppendsUnlinkedEvent() {
        TenantContext.set(new TenantContext(ORG, "patient.one", "PATIENT_IDENTITY", "corr-4"));
        when(repository.accountExists("patient.one")).thenReturn(true);
        when(repository.currentLink("patient.one", CHART)).thenReturn(
                Optional.of(new ChartLink("link-1", "patient.one", CHART, "linked", "INVITATION",
                        null, "2026-11-19T11:00:00Z")),
                Optional.of(new ChartLink("link-2", "patient.one", CHART, "unlinked", "PATIENT_REQUEST",
                        "no longer needed", "2026-11-19T12:00:00Z")));
        when(repository.insertLink(eq(ORG), anyString(), eq("patient.one"), eq(CHART),
                eq("unlinked"), eq("PATIENT_REQUEST"), anyString())).thenReturn(1);

        ChartLink link = service.unlink("patient.one", CHART, "no longer needed");

        assertEquals("unlinked", link.event());
        verify(repository).appendAudit(any(), eq("CHART_UNLINK"), eq(CHART), eq("SUCCESS"));
    }

    @Test
    void staffUnlinkRequiresRoleAndExistingLink() {
        staffAllowed(IdentityActionPolicy.LINK_UNLINK);
        when(repository.currentLink("patient.one", CHART)).thenReturn(Optional.empty());
        expect(404, "LINK_NOT_FOUND", () -> service.unlink("patient.one", CHART, "dispute"));

        // Re-stub so the existence check sees 'linked' and the returned view
        // sees 'unlinked' (two consecutive currentLink calls in one command).
        staffAllowed(IdentityActionPolicy.LINK_UNLINK);
        when(repository.currentLink("patient.one", CHART)).thenReturn(
                Optional.of(new ChartLink("link-1", "patient.one", CHART, "linked", "INVITATION",
                        null, "2026-11-19T11:00:00Z")),
                Optional.of(new ChartLink("link-2", "patient.one", CHART, "unlinked", "STAFF_ACTION",
                        "dispute", "2026-11-19T12:00:00Z")));
        when(repository.insertLink(eq(ORG), anyString(), eq("patient.one"), eq(CHART),
                eq("unlinked"), eq("STAFF_ACTION"), anyString())).thenReturn(1);
        ChartLink link = service.unlink("patient.one", CHART, "dispute");
        assertEquals("unlinked", link.event());
    }

    @Test
    void reverifyUnlinksAndIssuesFreshInvitation() {
        staffAllowed(IdentityActionPolicy.LINK_REVERIFY);
        when(repository.accountExists("patient.one")).thenReturn(true);
        when(repository.chartExists(CHART)).thenReturn(true);
        when(repository.currentLink("patient.one", CHART)).thenReturn(Optional.of(
                new ChartLink("link-1", "patient.one", CHART, "linked", "INVITATION", null, "2026-11-19T11:00:00Z")));
        when(repository.insertLink(eq(ORG), anyString(), eq("patient.one"), eq(CHART),
                eq("unlinked"), eq("REVERIFICATION"), anyString())).thenReturn(1);

        InvitationIssue issue = service.reverify("patient.one", CHART, "lost access");

        assertNotNull(issue.token());
        verify(repository).insertLink(eq(ORG), anyString(), eq("patient.one"), eq(CHART),
                eq("unlinked"), eq("REVERIFICATION"), eq("lost access"));
        verify(repository).appendAudit(any(), eq("LINK_REVERIFY"), eq(CHART), eq("SUCCESS"));
    }

    @Test
    void hasAccessAndLinksAreSelfOrStaffScoped() {
        // self access
        TenantContext.set(new TenantContext(ORG, "patient.one", "PATIENT_IDENTITY", "corr-5"));
        when(repository.accountExists("patient.one")).thenReturn(true);
        when(repository.hasAccess("patient.one", CHART)).thenReturn(true);
        assertTrue(service.hasAccess("patient.one", CHART));
        when(repository.links("patient.one")).thenReturn(List.of(
                new ChartLink("link-1", "patient.one", CHART, "linked", "INVITATION", null, "2026-11-19T11:00:00Z")));
        assertEquals(1, service.links("patient.one").size());

        // staff access to another account
        TenantContext.set(new TenantContext(ORG, STAFF, "PATIENT_IDENTITY", "corr-6"));
        staffAllowed(IdentityActionPolicy.LINK_READ);
        when(repository.hasAccess("patient.two", CHART)).thenReturn(false);
        assertFalse(service.hasAccess("patient.two", CHART));
    }

    @Test
    void unauthorizedReadIsDenied() {
        TenantContext.set(new TenantContext(ORG, "stranger", "PATIENT_IDENTITY", "corr-7"));
        when(repository.hasActiveMembership("stranger")).thenReturn(false);
        expect(403, "FORBIDDEN", () -> service.hasAccess("patient.two", CHART));
        verify(repository).appendAudit(any(), eq("LINK_READ"), eq(CHART), eq("DENIED"));
    }
}
