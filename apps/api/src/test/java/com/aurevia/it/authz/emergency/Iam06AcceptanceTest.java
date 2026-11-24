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

import com.aurevia.api.ApiException;
import com.aurevia.authz.access.AccessContracts.AccessDecision;
import com.aurevia.authz.access.AccessContracts.EmergencyGrantRequest;
import com.aurevia.authz.access.AccessContracts.EmergencyGrantView;
import com.aurevia.authz.access.AccessContracts.GrantRow;
import com.aurevia.authz.access.AccessContracts.IncidentRow;
import com.aurevia.authz.access.AccessContracts.SupportCheckRequest;
import com.aurevia.authz.access.AccessContracts.SupportIncidentRequest;
import com.aurevia.authz.access.AccessContracts.SupportIncidentView;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.authz.access.EmergencyAccessService;
import com.aurevia.authz.access.SupportAccessService;
import com.aurevia.tenant.TenantContext;

/**
 * Independent IAM-06 acceptance verification for the S-G0-08 scope.
 *
 * <p>The production slice lives in {@code com.aurevia.authz.access}
 * and is deliberately reused here rather than duplicated. This suite restates
 * the specification's acceptance matrix directly against the domain services
 * with synthetic fixtures, so a later change to those services is checked by a
 * second, separately owned entry point. PostgreSQL policy assertions remain in
 * the integration suite and {@code db/check.sh}.</p>
 */
class Iam06AcceptanceTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");

    private AccessRepository repository;
    private TransactionTemplate transactions;
    private EmergencyAccessService emergency;
    private SupportAccessService support;

    @BeforeEach
    void setUp() {
        repository = mock(AccessRepository.class);
        transactions = mock(TransactionTemplate.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        emergency = new EmergencyAccessService(repository, transactions, clock);
        support = new SupportAccessService(repository, transactions, clock);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        bind("clinician-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG_A, subject, "EMERGENCY_ACCESS", "iam06-corr"));
    }

    private void activeRoles(String... roles) {
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        when(repository.activeRoles(anyString())).thenReturn(List.of(roles));
    }

    private GrantRow grantRow() {
        return new GrantRow("grant-1", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600), null, null, null, null);
    }

    private EmergencyGrantRequest grantRequest() {
        return new EmergencyGrantRequest("MAIN", "patient in distress", 10);
    }

    private void approvedSiteAndFacility() {
        activeRoles("clinician");
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(repository.facilityExists("MAIN")).thenReturn(true);
    }

    @Test
    void a1QualifiedClinicianOpensTimeLimitedReasonedAlertedGrant() {
        approvedSiteAndFacility();
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.empty());
        when(repository.insertGrant(ORG_A, "clinician-a", "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600))).thenReturn(grantRow());

        EmergencyGrantView view = emergency.grant(grantRequest());

        assertEquals("MAIN", view.facilityCode());
        assertEquals("2026-11-20T09:00:00Z", view.grantedAt());
        assertEquals("2026-11-20T09:10:00Z", view.expiresAt());
        assertEquals("active", view.status());
        // Purpose, role:facility scope, correlation and outcome are all recorded.
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
        // The alert is a separate, committed audit fact, not the grant event itself.
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_ALERT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
    }

    @Test
    void a2InactiveMembershipAndWrongRoleFailClosedAndAreAudited() {
        when(repository.hasActiveMembership("clinician-a")).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> emergency.grant(grantRequest())).status());

        activeRoles("tenant_admin");
        assertEquals(403, assertThrows(ApiException.class,
                () -> emergency.grant(grantRequest())).status());
        verify(repository, org.mockito.Mockito.times(2)).appendAudit(any(), eq("EMERGENCY_ACCESS"),
                eq("EMERGENCY_GRANT"), eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"),
                eq("MAIN"), eq("DENIED"));
        verify(repository, never()).insertGrant(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any());
    }

    @Test
    void a3UnapprovedSiteCannotEnableEmergencyAccess() {
        activeRoles("clinician");
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn(null, "false", "TRUE ");
        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(403, assertThrows(ApiException.class,
                    () -> emergency.grant(grantRequest())).status());
        }
        verify(repository, never()).facilityExists(anyString());
    }

    @Test
    void a4FacilityOutsideActiveOrganizationIsConcealed() {
        activeRoles("clinician");
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(repository.facilityExists("MAIN")).thenReturn(false);
        ApiException denial = assertThrows(ApiException.class, () -> emergency.grant(grantRequest()));
        assertEquals(404, denial.status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("MAIN"), eq("NOT_FOUND"));
    }

    @Test
    void a5OnlyOneUnexpiredUnrevokedGrantMayExistAtATime() {
        approvedSiteAndFacility();
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.of(grantRow()));
        assertEquals(409, assertThrows(ApiException.class,
                () -> emergency.grant(grantRequest())).status());

        GrantRow expired = new GrantRow("old", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW.minusSeconds(3600), NOW.minusSeconds(1), null, null, null, null);
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.of(expired));
        when(repository.insertGrant(ORG_A, "clinician-a", "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600))).thenReturn(grantRow());
        assertEquals("grant-1", emergency.grant(grantRequest()).id());
    }

    @Test
    void a6ActiveDecisionReportsExplicitReasonAtEveryBoundary() {
        activeRoles("clinician");
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.empty());
        assertEquals(new AccessDecision(false, "NO_GRANT"), emergency.active());

        GrantRow revoked = new GrantRow("grant-1", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW, NOW.plusSeconds(600), NOW, null, null, null);
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.of(revoked));
        assertEquals(new AccessDecision(false, "REVOKED"), emergency.active());

        GrantRow expired = new GrantRow("grant-1", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW.minusSeconds(1200), NOW, null, null, null, null);
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.of(expired));
        assertEquals(new AccessDecision(false, "EXPIRED"), emergency.active());

        when(repository.latestGrant("clinician-a")).thenReturn(Optional.of(grantRow()));
        assertEquals(new AccessDecision(true, "ACTIVE"), emergency.active());
    }

    @Test
    void a7RevocationAndReviewAreRoleBoundAndIndependentReviewerCannotBeGrantee() {
        bind("clinician-a");
        activeRoles("clinician");
        when(repository.revokeGrant("grant-1", "no longer needed", NOW)).thenReturn(1);
        GrantRow revoked = new GrantRow("grant-1", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW, NOW.plusSeconds(600), NOW, null, null, null);
        when(repository.findGrant("grant-1")).thenReturn(Optional.of(revoked));
        assertEquals("revoked", emergency.revoke("grant-1",
                new com.aurevia.authz.access.AccessContracts.RevokeRequest("no longer needed")).status());

        // The grantee (a clinician) cannot review; only a privacy role may review.
        assertEquals(403, assertThrows(ApiException.class, () -> emergency.review("grant-1",
                new com.aurevia.authz.access.AccessContracts.ReviewRequest("checked"))).status());
        bind("auditor-a");
        activeRoles("privacy_auditor");
        when(repository.reviewGrant("grant-1", "auditor-a", "checked", NOW)).thenReturn(1);
        GrantRow reviewed = new GrantRow("grant-1", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW, NOW.plusSeconds(600), null, "auditor-a", NOW, "checked");
        when(repository.findGrant("grant-1")).thenReturn(Optional.of(reviewed));
        assertEquals("reviewed", emergency.review("grant-1",
                new com.aurevia.authz.access.AccessContracts.ReviewRequest("checked")).status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_REVIEW"),
                eq("privacy_auditor:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
    }

    @Test
    void a8SupportAccessIsDefaultDenyUntilASeparatelyApprovedUnexpiredIncident() {
        bind("admin-a");
        activeRoles("tenant_admin");
        when(repository.activeIncident("INC-1", "admin-a", NOW)).thenReturn(Optional.empty());
        AccessDecision denied = support.checkAccess(new SupportCheckRequest("INC-1"));
        assertFalse(denied.allowed());
        assertEquals("NO_APPROVED_INCIDENT", denied.reason());

        IncidentRow approved = new IncidentRow("id-1", "INC-1", "admin-a", "restore chart", "approved",
                "auditor-a", NOW, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(repository.activeIncident("INC-1", "admin-a", NOW)).thenReturn(Optional.of(approved));
        AccessDecision allowed = support.checkAccess(new SupportCheckRequest("INC-1"));
        assertTrue(allowed.allowed());
        assertEquals("APPROVED_INCIDENT", allowed.reason());

        IncidentRow selfApproved = new IncidentRow("id-1", "INC-1", "admin-a", "restore chart", "approved",
                "admin-a", NOW, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(repository.activeIncident("INC-1", "admin-a", NOW)).thenReturn(Optional.of(selfApproved));
        assertEquals("SELF_APPROVAL",
                support.checkAccess(new SupportCheckRequest("INC-1")).reason());
    }

    @Test
    void a9IncidentCreationAndApprovalRequireDifferentRoles() {
        bind("admin-a");
        activeRoles("tenant_admin");
        assertEquals(403, assertThrows(ApiException.class,
                () -> support.approveIncident("INC-1")).status());
        IncidentRow pending = new IncidentRow("id-1", "INC-1", "admin-a", "restore chart",
                "pending", null, null, NOW, NOW.plusSeconds(3600));
        when(repository.insertIncident(ORG_A, "INC-1", "admin-a", "restore chart", NOW,
                NOW.plusSeconds(3600))).thenReturn(pending);
        SupportIncidentView created = support.createIncident(
                new SupportIncidentRequest("INC-1", "restore chart", 60));
        assertEquals("pending", created.status());

        bind("auditor-a");
        activeRoles("privacy_auditor");
        assertEquals(403, assertThrows(ApiException.class,
                () -> support.createIncident(new SupportIncidentRequest("INC-2", "why", 60))).status());
        when(repository.approveIncident("INC-1", "auditor-a", NOW)).thenReturn(1);
        IncidentRow approved = new IncidentRow("id-1", "INC-1", "admin-a", "restore chart", "approved",
                "auditor-a", NOW, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(repository.findIncident("INC-1")).thenReturn(Optional.of(approved));
        assertEquals("approved", support.approveIncident("INC-1").status());
    }

    @Test
    void a10IdentityAndTenantAreDerivedFromContextNotFromTheCommand() {
        bind("clinician-b");
        approvedSiteAndFacility();
        when(repository.latestGrant("clinician-b")).thenReturn(Optional.empty());
        GrantRow other = new GrantRow("grant-2", "clinician-b", "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600), null, null, null, null);
        when(repository.insertGrant(ORG_A, "clinician-b", "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600))).thenReturn(other);

        assertEquals("grant-2", emergency.grant(grantRequest()).id());
        // Neither organization nor subject is accepted from the request body; the
        // repository receives exactly the authenticated context values.
        verify(repository).insertGrant(eq(ORG_A), eq("clinician-b"), anyString(), anyString(),
                anyString(), anyString(), any(), any());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-2"), eq("SUCCESS"));
    }
}
