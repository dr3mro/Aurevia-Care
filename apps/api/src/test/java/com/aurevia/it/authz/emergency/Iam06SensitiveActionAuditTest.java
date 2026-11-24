package com.aurevia.it.authz.emergency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.access.AccessContracts.AccessDecision;
import com.aurevia.authz.access.AccessContracts.EmergencyGrantRequest;
import com.aurevia.authz.access.AccessContracts.GrantRow;
import com.aurevia.authz.access.AccessContracts.ReviewRequest;
import com.aurevia.authz.access.AccessContracts.RevokeRequest;
import com.aurevia.authz.access.AccessContracts.SupportCheckRequest;
import com.aurevia.authz.access.AccessContracts.SupportIncidentRequest;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.authz.access.EmergencyAccessService;
import com.aurevia.authz.access.SupportAccessService;
import com.aurevia.tenant.TenantContext;

/**
 * Independently owned IAM-06 audit-evidence verification for the sensitive-action
 * paths that the two inherited scoped suites do not capture with an explicit
 * {@link TenantContext} argument.
 *
 * <p>Specification IAM-06 and OPS-02 require the business purpose, role, facility and
 * acting user to be recorded for every sensitive action. {@code Iam06AuditEvidenceTest}
 * proves that only for grant, membership-denied grant and support-check denial. This
 * suite extends the same capture to the explicit access decisions, the stacked-grant
 * conflict, the site-configuration and facility denials, revocation, review, incident
 * creation/approval and the authorized support decision. Each capture asserts the
 * authenticated organization, subject, purpose and correlation id, so a future change
 * that substitutes a context value is caught by a second, separately owned entry
 * point. The production slice in {@code authz.access} is reused, never duplicated or
 * edited; every fixture is synthetic.</p>
 */
class Iam06SensitiveActionAuditTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CORRELATION = "iam06-sensitive-audit";
    private static final String PURPOSE = "EMERGENCY_ACCESS";
    private static final String SUPPORT_PURPOSE = "SUPPORT_ACCESS";
    private static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");

    private AccessRepository repository;
    private EmergencyAccessService emergency;
    private SupportAccessService support;

    /** Wire the two domain services against a mocked repository and a synchronous transaction. */
    @BeforeEach
    void setUp() {
        repository = mock(AccessRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        emergency = new EmergencyAccessService(repository, transactions, clock);
        support = new SupportAccessService(repository, transactions, clock);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        bind("clinician-a");
    }

    /** Clear the thread-local tenant context after each scenario. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "EMERGENCY_ACCESS", CORRELATION));
    }

    private void assertContext(TenantContext context, String subject) {
        assertEquals(ORG, context.organizationId(), "audited organization must come from the active context");
        assertEquals(subject, context.subject(), "audited user must come from the authenticated subject");
        assertEquals("EMERGENCY_ACCESS", context.purpose(), "business purpose must come from the context");
        assertEquals(CORRELATION, context.correlationId(), "correlation id must be propagated to the audit");
    }

    private GrantRow grantRow() {
        return new GrantRow("grant-1", "clinician-a", "MAIN", PURPOSE, "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600), null, null, null, null);
    }

    private EmergencyGrantRequest grantRequest() {
        return new EmergencyGrantRequest("MAIN", "patient in distress", 10);
    }

    private void clinician() {
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        when(repository.activeRoles(anyString())).thenReturn(List.of("clinician"));
    }

    @Test
    void noGrantDecisionAuditsTheActingClinicianAndAnExplicitDeniedOutcome() {
        clinician();
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.empty());

        assertEquals(new AccessDecision(false, "NO_GRANT"), emergency.active());

        ArgumentCaptor<TenantContext> context = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(context.capture(), eq(PURPOSE), eq("EMERGENCY_ACTIVE_CHECK"),
                eq("clinician:SYSTEM"), eq("EMERGENCY_ACCESS_GRANT"), eq("clinician-a"), eq("DENIED"));
        assertContext(context.getValue(), "clinician-a");
    }

    @Test
    void revokedAndExpiredDecisionsAuditTheGrantAndTheActingClinician() {
        clinician();
        GrantRow revoked = new GrantRow("grant-1", "clinician-a", "MAIN", PURPOSE, "why",
                "clinician", NOW, NOW.plusSeconds(600), NOW, null, null, null);
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.of(revoked));

        assertEquals(new AccessDecision(false, "REVOKED"), emergency.active());
        ArgumentCaptor<TenantContext> revokedContext = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(revokedContext.capture(), eq(PURPOSE), eq("EMERGENCY_ACTIVE_CHECK"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("DENIED"));
        assertContext(revokedContext.getValue(), "clinician-a");

        GrantRow expired = new GrantRow("grant-2", "clinician-a", "MAIN", PURPOSE, "why",
                "clinician", NOW.minusSeconds(1200), NOW, null, null, null, null);
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.of(expired));

        assertEquals(new AccessDecision(false, "EXPIRED"), emergency.active());
        ArgumentCaptor<TenantContext> expiredContext = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(expiredContext.capture(), eq(PURPOSE), eq("EMERGENCY_ACTIVE_CHECK"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-2"), eq("DENIED"));
        assertContext(expiredContext.getValue(), "clinician-a");
    }

    @Test
    void activeDecisionAuditsSuccessWithTheGrantsOwnFacilityScope() {
        clinician();
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.of(grantRow()));

        assertEquals(new AccessDecision(true, "ACTIVE"), emergency.active());

        ArgumentCaptor<TenantContext> context = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(context.capture(), eq(PURPOSE), eq("EMERGENCY_ACTIVE_CHECK"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
        assertContext(context.getValue(), "clinician-a");
    }

    @Test
    void stackedGrantAuditsConflictAgainstTheExistingGrantUnderTheActingContext() {
        clinician();
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(repository.facilityExists("MAIN")).thenReturn(true);
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.of(grantRow()));

        assertEquals(409, assertThrows(ApiException.class,
                () -> emergency.grant(grantRequest())).status());

        ArgumentCaptor<TenantContext> context = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(context.capture(), eq(PURPOSE), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("CONFLICT"));
        assertContext(context.getValue(), "clinician-a");
    }

    @Test
    void unapprovedSiteAndUnknownFacilityAuditsCarryTheActingContext() {
        clinician();
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn(null);

        assertEquals(403, assertThrows(ApiException.class,
                () -> emergency.grant(grantRequest())).status());
        ArgumentCaptor<TenantContext> siteContext = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(siteContext.capture(), eq(PURPOSE), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("MAIN"), eq("DENIED"));
        assertContext(siteContext.getValue(), "clinician-a");

        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(repository.facilityExists("MAIN")).thenReturn(false);

        assertEquals(404, assertThrows(ApiException.class,
                () -> emergency.grant(grantRequest())).status());
        ArgumentCaptor<TenantContext> facilityContext = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(facilityContext.capture(), eq(PURPOSE), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("MAIN"), eq("NOT_FOUND"));
        assertContext(facilityContext.getValue(), "clinician-a");
    }

    @Test
    void revokeAndReviewAuditsCarryTheActingClinicianAndReviewerContexts() {
        clinician();
        when(repository.revokeGrant("grant-1", "no longer needed", NOW)).thenReturn(1);
        when(repository.findGrant("grant-1")).thenReturn(Optional.of(grantRow()));

        emergency.revoke("grant-1", new RevokeRequest("no longer needed"));
        ArgumentCaptor<TenantContext> revokeContext = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(revokeContext.capture(), eq(PURPOSE), eq("EMERGENCY_REVOKE"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
        assertContext(revokeContext.getValue(), "clinician-a");

        bind("auditor-a");
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        when(repository.activeRoles(anyString())).thenReturn(List.of("privacy_auditor"));
        when(repository.reviewGrant("grant-1", "auditor-a", "checked", NOW)).thenReturn(1);
        when(repository.findGrant("grant-1")).thenReturn(Optional.of(new GrantRow(
                "grant-1", "clinician-a", "MAIN", PURPOSE, "why", "clinician",
                NOW, NOW.plusSeconds(600), null, "auditor-a", NOW, "checked")));

        emergency.review("grant-1", new ReviewRequest("checked"));
        ArgumentCaptor<TenantContext> reviewContext = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(reviewContext.capture(), eq(PURPOSE), eq("EMERGENCY_REVIEW"),
                eq("privacy_auditor:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
        assertContext(reviewContext.getValue(), "auditor-a");
    }

    @Test
    void incidentCreationAndApprovalAuditsCarryTheActingAdminAndAuditorContexts() {
        bind("admin-a");
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        when(repository.activeRoles(anyString())).thenReturn(List.of("tenant_admin"));
        var pending = new com.aurevia.authz.access.AccessContracts.IncidentRow(
                "id-1", "INC-1", "admin-a", "restore chart", "pending", null, null, NOW, NOW.plusSeconds(3600));
        when(repository.insertIncident(ORG, "INC-1", "admin-a", "restore chart", NOW,
                NOW.plusSeconds(3600))).thenReturn(pending);

        support.createIncident(new SupportIncidentRequest("INC-1", "restore chart", 60));
        ArgumentCaptor<TenantContext> createContext = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(createContext.capture(), eq(SUPPORT_PURPOSE),
                eq("SUPPORT_INCIDENT_CREATE"), eq("tenant_admin:INC-1"),
                eq("SUPPORT_ACCESS_INCIDENT"), eq("INC-1"), eq("SUCCESS"));
        assertContext(createContext.getValue(), "admin-a");

        bind("auditor-a");
        when(repository.activeRoles(anyString())).thenReturn(List.of("privacy_auditor"));
        when(repository.approveIncident("INC-1", "auditor-a", NOW)).thenReturn(1);
        when(repository.findIncident("INC-1")).thenReturn(Optional.of(
                new com.aurevia.authz.access.AccessContracts.IncidentRow(
                        "id-1", "INC-1", "admin-a", "restore chart", "approved", "auditor-a", NOW,
                        NOW.minusSeconds(60), NOW.plusSeconds(3600))));

        support.approveIncident("INC-1");
        ArgumentCaptor<TenantContext> approveContext = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(approveContext.capture(), eq(SUPPORT_PURPOSE),
                eq("SUPPORT_INCIDENT_APPROVE"), eq("privacy_auditor:INC-1"),
                eq("SUPPORT_ACCESS_INCIDENT"), eq("INC-1"), eq("SUCCESS"));
        assertContext(approveContext.getValue(), "auditor-a");
    }

    @Test
    void authorizedSupportDecisionAuditsTheBoundIncidentAndTheActingContext() {
        bind("admin-a");
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        var approved = new com.aurevia.authz.access.AccessContracts.IncidentRow(
                "id-1", "INC-1", "admin-a", "restore chart", "approved", "auditor-a", NOW,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(repository.activeIncident("INC-1", "admin-a", NOW)).thenReturn(Optional.of(approved));

        AccessDecision decision = support.checkAccess(new SupportCheckRequest("INC-1"));
        assertTrue(decision.allowed());
        assertEquals("APPROVED_INCIDENT", decision.reason());

        ArgumentCaptor<TenantContext> context = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(context.capture(), eq(SUPPORT_PURPOSE),
                eq("SUPPORT_ACCESS_CHECK"), eq("platform_support:INC-1"),
                eq("SUPPORT_ACCESS_INCIDENT"), eq("INC-1"), eq("SUCCESS"));
        assertContext(context.getValue(), "admin-a");
        assertFalse(approved.approvedBy().equals("admin-a"));
    }
}
