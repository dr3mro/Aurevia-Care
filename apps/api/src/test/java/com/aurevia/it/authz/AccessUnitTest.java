package com.aurevia.it.authz;

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
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.access.AccessContracts;
import com.aurevia.authz.access.AccessContracts.AccessDecision;
import com.aurevia.authz.access.AccessContracts.EmergencyGrantRequest;
import com.aurevia.authz.access.AccessContracts.EmergencyGrantView;
import com.aurevia.authz.access.AccessContracts.GrantRow;
import com.aurevia.authz.access.AccessContracts.IncidentRow;
import com.aurevia.authz.access.AccessContracts.ReviewRequest;
import com.aurevia.authz.access.AccessContracts.RevokeRequest;
import com.aurevia.authz.access.AccessContracts.SupportCheckRequest;
import com.aurevia.authz.access.AccessContracts.SupportIncidentRequest;
import com.aurevia.authz.access.AccessContracts.SupportIncidentView;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.authz.access.AccessRules;
import com.aurevia.authz.access.AccessController;
import com.aurevia.authz.access.EmergencyAccessService;
import com.aurevia.authz.access.SupportAccessService;
import com.aurevia.tenant.TenantContext;

/**
 * Fast, database-free acceptance for S-G0-08: validation, fail-closed authorization,
 * time limits, separation of duties and auditing (IAM-06). PostgreSQL suites are in
 * {@code AccessIntegrationTest} and carry the {@code integration} tag.
 */
class AccessUnitTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-a";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private AccessRepository repository;
    private TransactionTemplate transactions;
    private Clock clock;
    private EmergencyAccessService emergency;
    private SupportAccessService support;

    @BeforeEach
    void setUp() {
        repository = mock(AccessRepository.class);
        transactions = mock(TransactionTemplate.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        emergency = new EmergencyAccessService(repository, transactions, clock);
        support = new SupportAccessService(repository, transactions, clock);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "EMERGENCY_ACCESS", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "EMERGENCY_ACCESS", "corr-1"));
    }

    private void membership(String... roles) {
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        when(repository.activeRoles(anyString())).thenReturn(List.of(roles));
    }

    private GrantRow grant() {
        return new GrantRow("grant-1", ACTOR, "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600), null, null, null, null);
    }

    private IncidentRow incident(String approvedBy) {
        return new IncidentRow("incident-1", "INC-100", "admin-a", "restore chart",
                "approved", approvedBy, NOW, NOW.minusSeconds(60), NOW.plusSeconds(3600));
    }

    private IncidentRow pendingIncident() {
        return new IncidentRow("incident-1", "INC-100", "admin-a", "restore chart",
                "pending", null, null, NOW, NOW.plusSeconds(3600));
    }

    private EmergencyGrantRequest validGrant() {
        return new EmergencyGrantRequest("MAIN", "patient in distress", 10);
    }

    private SupportIncidentRequest validIncident() {
        return new SupportIncidentRequest("INC-100", "restore chart", 60);
    }

    // --- AccessRules ---

    @Test
    void rulesAcceptValidInputs() {
        org.junit.jupiter.api.Assertions.assertNotNull(new AccessRules());
        org.junit.jupiter.api.Assertions.assertNotNull(new AccessContracts());
        AccessRules.validateFacility("MAIN");
        AccessRules.validateReason("patient in distress", "INVALID_REASON");
        AccessRules.validateDuration(60);
        AccessRules.validateWindow(240);
        AccessRules.validateReference("INC-100");
        assertTrue(AccessRules.isEnabled("TRUE"));
        assertTrue(AccessRules.isClinician(List.of("reception", "clinician")));
        assertTrue(AccessRules.isReviewer(List.of("privacy_auditor")));
        assertTrue(AccessRules.isReviewer(List.of("tenant_admin")));
        assertTrue(AccessRules.isAdmin(List.of("tenant_admin")));
    }

    @Test
    void rulesRejectInvalidFacility() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> AccessRules.validateFacility(null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> AccessRules.validateFacility("  ")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> AccessRules.validateFacility("x".repeat(65))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> AccessRules.validateFacility("BAD CODE")).status());
    }

    @Test
    void rulesRejectInvalidReasonAndNote() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> AccessRules.validateReason(null, "INVALID_REASON")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> AccessRules.validateReason(" ", "INVALID_REASON")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> AccessRules.validateReason("x".repeat(501), "INVALID_REASON")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> AccessRules.validateReason("", "INVALID_NOTE")).status());
    }

    @Test
    void rulesRejectOutOfRangeDurationsAndReference() {
        assertEquals(422, assertThrows(ApiException.class, () -> AccessRules.validateDuration(0)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> AccessRules.validateDuration(61)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> AccessRules.validateWindow(0)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> AccessRules.validateWindow(241)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> AccessRules.validateReference(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> AccessRules.validateReference("ab")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> AccessRules.validateReference("BAD REF")).status());
    }

    @Test
    void rulesDenyUnknownFlagsAndRoles() {
        assertFalse(AccessRules.isEnabled(null));
        assertFalse(AccessRules.isEnabled("false"));
        assertFalse(AccessRules.isClinician(List.of("reception")));
        assertFalse(AccessRules.isReviewer(List.of("clinician")));
        assertFalse(AccessRules.isAdmin(List.of("privacy_auditor")));
    }

    // --- Emergency grant ---

    @Test
    void grantRejectsInvalidInputBeforeAnyDatabaseWork() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> emergency.grant(new EmergencyGrantRequest("BAD CODE", "why", 10))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> emergency.grant(new EmergencyGrantRequest("MAIN", " ", 10))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> emergency.grant(new EmergencyGrantRequest("MAIN", "why", 0))).status());
        verify(repository, never()).insertGrant(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any());
    }

    @Test
    void grantDeniesInactiveMembershipAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> emergency.grant(validGrant())).status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("MAIN"), eq("DENIED"));
        verify(repository, never()).insertGrant(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any());
    }

    @Test
    void grantDeniesNonClinicianRoleAndAudits() {
        membership("tenant_admin");
        assertEquals(403, assertThrows(ApiException.class, () -> emergency.grant(validGrant())).status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("MAIN"), eq("DENIED"));
    }

    @Test
    void grantDeniesWhenSiteConfigurationIsNotApproved() {
        membership("clinician");
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn(null, "false");
        assertEquals(403, assertThrows(ApiException.class, () -> emergency.grant(validGrant())).status());
        assertEquals(403, assertThrows(ApiException.class, () -> emergency.grant(validGrant())).status());
        verify(repository, never()).facilityExists(anyString());
    }

    @Test
    void grantDeniesFacilityOutsideTheActiveOrganization() {
        membership("clinician");
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(repository.facilityExists("MAIN")).thenReturn(false);
        assertEquals(404, assertThrows(ApiException.class, () -> emergency.grant(validGrant())).status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("MAIN"), eq("NOT_FOUND"));
    }

    @Test
    void grantCreatesTimeLimitedRecordAndAuditsGrantAndAlert() {
        membership("clinician");
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(repository.facilityExists("MAIN")).thenReturn(true);
        when(repository.latestGrant(ACTOR)).thenReturn(Optional.empty());
        when(repository.insertGrant(ORG, ACTOR, "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600))).thenReturn(grant());

        EmergencyGrantView view = emergency.grant(validGrant());

        assertEquals("grant-1", view.id());
        assertEquals(ACTOR, view.subject());
        assertEquals("MAIN", view.facilityCode());
        assertEquals("clinician", view.roleKey());
        assertEquals("patient in distress", view.reason());
        assertEquals("2026-11-19T10:00:00Z", view.grantedAt());
        assertEquals("2026-11-19T10:10:00Z", view.expiresAt());
        assertEquals("active", view.status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_ALERT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
    }

    @Test
    void grantRejectsStackedActiveAccessButAllowsAfterExpiry() {
        membership("clinician");
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(repository.facilityExists("MAIN")).thenReturn(true);
        when(repository.latestGrant(ACTOR)).thenReturn(Optional.of(grant()));
        assertEquals(409, assertThrows(ApiException.class, () -> emergency.grant(validGrant())).status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("CONFLICT"));
        verify(repository, never()).insertGrant(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any());

        GrantRow expired = new GrantRow("old-1", ACTOR, "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW.minusSeconds(1200), NOW.minusSeconds(600), null, null, null, null);
        when(repository.latestGrant(ACTOR)).thenReturn(Optional.of(expired));
        when(repository.insertGrant(ORG, ACTOR, "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600))).thenReturn(grant());
        assertEquals("grant-1", emergency.grant(validGrant()).id());
    }

    @Test
    void grantAllowsAfterAPriorGrantWasRevoked() {
        membership("clinician");
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(repository.facilityExists("MAIN")).thenReturn(true);
        GrantRow revoked = new GrantRow("old-1", ACTOR, "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW.minusSeconds(1200), NOW.plusSeconds(600), NOW.minusSeconds(600),
                null, null, null);
        when(repository.latestGrant(ACTOR)).thenReturn(Optional.of(revoked));
        when(repository.insertGrant(ORG, ACTOR, "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600))).thenReturn(grant());
        assertEquals("grant-1", emergency.grant(validGrant()).id());
    }

    // --- Emergency active check ---

    @Test
    void activeDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> emergency.active()).status());
        verify(repository, never()).latestGrant(anyString());
    }

    @Test
    void activeReportsNoGrant() {
        membership("clinician");
        when(repository.latestGrant(ACTOR)).thenReturn(Optional.empty());
        AccessDecision decision = emergency.active();
        assertFalse(decision.allowed());
        assertEquals("NO_GRANT", decision.reason());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_ACTIVE_CHECK"),
                eq("clinician:SYSTEM"), eq("EMERGENCY_ACCESS_GRANT"), eq(ACTOR), eq("DENIED"));
    }

    @Test
    void activeReportsRevokedGrant() {
        membership("clinician");
        GrantRow revoked = new GrantRow("grant-1", ACTOR, "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW, NOW.plusSeconds(600), NOW, null, null, null);
        when(repository.latestGrant(ACTOR)).thenReturn(Optional.of(revoked));
        AccessDecision decision = emergency.active();
        assertFalse(decision.allowed());
        assertEquals("REVOKED", decision.reason());
    }

    @Test
    void activeReportsExpiredGrant() {
        membership("clinician");
        GrantRow expired = new GrantRow("grant-1", ACTOR, "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW.minusSeconds(1200), NOW.minusSeconds(600), null, null, null, null);
        when(repository.latestGrant(ACTOR)).thenReturn(Optional.of(expired));
        AccessDecision decision = emergency.active();
        assertFalse(decision.allowed());
        assertEquals("EXPIRED", decision.reason());
    }

    @Test
    void activeAllowsUnrevokedUnexpiredGrant() {
        membership("clinician");
        when(repository.latestGrant(ACTOR)).thenReturn(Optional.of(grant()));
        AccessDecision decision = emergency.active();
        assertTrue(decision.allowed());
        assertEquals("ACTIVE", decision.reason());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_ACTIVE_CHECK"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
    }

    @Test
    void revokeReportsMissingProjectionAfterUpdate() {
        membership("clinician");
        when(repository.revokeGrant("grant-1", "done", NOW)).thenReturn(1);
        when(repository.findGrant("grant-1")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> emergency.revoke("grant-1", new RevokeRequest("done"))).status());
    }

    // --- Emergency revoke / review ---

    @Test
    void revokeRejectsBlankReason() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> emergency.revoke("grant-1", new RevokeRequest(" "))).status());
    }

    @Test
    void revokeDeniesNonClinicianAndInactiveMembership() {
        membership("tenant_admin");
        assertEquals(403, assertThrows(ApiException.class,
                () -> emergency.revoke("grant-1", new RevokeRequest("no longer needed"))).status());
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> emergency.revoke("grant-1", new RevokeRequest("no longer needed"))).status());
    }

    @Test
    void revokeReportsUnknownGrant() {
        membership("clinician");
        when(repository.revokeGrant("missing", "no longer needed", NOW)).thenReturn(0);
        assertEquals(404, assertThrows(ApiException.class,
                () -> emergency.revoke("missing", new RevokeRequest("no longer needed"))).status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_REVOKE"),
                eq("clinician:SYSTEM"), eq("EMERGENCY_ACCESS_GRANT"), eq("missing"), eq("NOT_FOUND"));
    }

    @Test
    void revokeRecordsReasonAndReturnsProjection() {
        membership("clinician");
        when(repository.revokeGrant("grant-1", "no longer needed", NOW)).thenReturn(1);
        GrantRow revoked = new GrantRow("grant-1", ACTOR, "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW, NOW.plusSeconds(600), NOW, null, null, null);
        when(repository.findGrant("grant-1")).thenReturn(Optional.of(revoked));
        EmergencyGrantView view = emergency.revoke("grant-1", new RevokeRequest("no longer needed"));
        assertEquals("revoked", view.status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_REVOKE"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
    }

    @Test
    void reviewDeniesClinicianAndInvalidNote() {
        membership("clinician");
        assertEquals(403, assertThrows(ApiException.class,
                () -> emergency.review("grant-1", new ReviewRequest("checked"))).status());
        membership("privacy_auditor");
        assertEquals(422, assertThrows(ApiException.class,
                () -> emergency.review("grant-1", new ReviewRequest(" "))).status());
    }

    @Test
    void reviewReportsUnknownGrant() {
        membership("tenant_admin");
        when(repository.reviewGrant("missing", ACTOR, "checked", NOW)).thenReturn(0);
        assertEquals(404, assertThrows(ApiException.class,
                () -> emergency.review("missing", new ReviewRequest("checked"))).status());
    }

    @Test
    void reviewRecordsReviewerAndReturnsProjection() {
        membership("privacy_auditor");
        when(repository.reviewGrant("grant-1", ACTOR, "checked", NOW)).thenReturn(1);
        GrantRow reviewed = new GrantRow("grant-1", ACTOR, "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW, NOW.plusSeconds(600), null, ACTOR, NOW, "checked");
        when(repository.findGrant("grant-1")).thenReturn(Optional.of(reviewed));
        EmergencyGrantView view = emergency.review("grant-1", new ReviewRequest("checked"));
        assertEquals("reviewed", view.status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_REVIEW"),
                eq("privacy_auditor:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
    }

    @Test
    void tenantAdminReviewRecordsTenantAdminRole() {
        membership("tenant_admin");
        when(repository.reviewGrant("grant-1", ACTOR, "checked", NOW)).thenReturn(1);
        GrantRow reviewed = new GrantRow("grant-1", ACTOR, "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW, NOW.plusSeconds(600), null, ACTOR, NOW, "checked");
        when(repository.findGrant("grant-1")).thenReturn(Optional.of(reviewed));
        assertEquals("reviewed", emergency.review("grant-1", new ReviewRequest("checked")).status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_REVIEW"),
                eq("tenant_admin:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
    }

    @Test
    void reviewReportsMissingProjectionAfterUpdate() {
        membership("privacy_auditor");
        when(repository.reviewGrant("grant-1", ACTOR, "checked", NOW)).thenReturn(1);
        when(repository.findGrant("grant-1")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> emergency.review("grant-1", new ReviewRequest("checked"))).status());
    }

    @Test
    void reviewQueueDeniesClinicianAndListsGrantsForReviewers() {
        membership("clinician");
        assertEquals(403, assertThrows(ApiException.class, () -> emergency.reviewQueue()).status());
        membership("privacy_auditor");
        GrantRow expired = new GrantRow("old-1", ACTOR, "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW.minusSeconds(1200), NOW.minusSeconds(600), null, null, null, null);
        when(repository.listGrants()).thenReturn(List.of(grant(), expired));
        List<EmergencyGrantView> views = emergency.reviewQueue();
        assertEquals(2, views.size());
        assertEquals("grant-1", views.get(0).id());
        assertEquals("expired", views.get(1).status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_REVIEW_QUEUE"),
                eq("reviewer:SYSTEM"), eq("EMERGENCY_ACCESS_GRANT"), eq("-"), eq("SUCCESS"));
    }

    // --- Support access ---

    @Test
    void createIncidentRejectsInvalidInput() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> support.createIncident(new SupportIncidentRequest("BAD REF", "why", 60))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> support.createIncident(new SupportIncidentRequest("INC-100", " ", 60))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> support.createIncident(new SupportIncidentRequest("INC-100", "why", 0))).status());
        verify(repository, never()).insertIncident(anyString(), anyString(), anyString(), anyString(),
                any(), any());
    }

    @Test
    void createIncidentDeniesInactiveMembershipAndNonAdmin() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> support.createIncident(validIncident())).status());
        membership("clinician");
        assertEquals(403, assertThrows(ApiException.class,
                () -> support.createIncident(validIncident())).status());
    }

    @Test
    void createIncidentRecordsRequesterAndPendingStatus() {
        bind("admin-a");
        membership("tenant_admin");
        when(repository.insertIncident(ORG, "INC-100", "admin-a", "restore chart", NOW,
                NOW.plusSeconds(3600))).thenReturn(pendingIncident());
        SupportIncidentView view = support.createIncident(validIncident());
        assertEquals("INC-100", view.reference());
        assertEquals("admin-a", view.subject());
        assertEquals("pending", view.status());
        verify(repository).appendAudit(any(), eq("SUPPORT_ACCESS"), eq("SUPPORT_INCIDENT_CREATE"),
                eq("tenant_admin:INC-100"), eq("SUPPORT_ACCESS_INCIDENT"), eq("INC-100"), eq("SUCCESS"));
    }

    @Test
    void approveIncidentDeniesTenantAdmin() {
        membership("tenant_admin");
        assertEquals(403, assertThrows(ApiException.class,
                () -> support.approveIncident("INC-100")).status());
    }

    @Test
    void approveIncidentReportsUnknownOrAlreadyApprovedIncident() {
        bind("auditor-a");
        membership("privacy_auditor");
        when(repository.approveIncident("INC-100", "auditor-a", NOW)).thenReturn(0);
        assertEquals(409, assertThrows(ApiException.class,
                () -> support.approveIncident("INC-100")).status());
        verify(repository).appendAudit(any(), eq("SUPPORT_ACCESS"), eq("SUPPORT_INCIDENT_APPROVE"),
                eq("privacy_auditor:INC-100"), eq("SUPPORT_ACCESS_INCIDENT"), eq("INC-100"), eq("CONFLICT"));
    }

    @Test
    void approveIncidentRecordsApprover() {
        bind("auditor-a");
        membership("privacy_auditor");
        when(repository.approveIncident("INC-100", "auditor-a", NOW)).thenReturn(1);
        when(repository.findIncident("INC-100")).thenReturn(Optional.of(incident("auditor-a")));
        SupportIncidentView view = support.approveIncident("INC-100");
        assertEquals("approved", view.status());
        assertEquals("auditor-a", view.approvedBy());
        verify(repository).appendAudit(any(), eq("SUPPORT_ACCESS"), eq("SUPPORT_INCIDENT_APPROVE"),
                eq("privacy_auditor:INC-100"), eq("SUPPORT_ACCESS_INCIDENT"), eq("INC-100"), eq("SUCCESS"));
    }

    @Test
    void approveIncidentReportsMissingProjectionAfterUpdate() {
        bind("auditor-a");
        membership("privacy_auditor");
        when(repository.approveIncident("INC-100", "auditor-a", NOW)).thenReturn(1);
        when(repository.findIncident("INC-100")).thenReturn(Optional.empty());
        assertEquals(409, assertThrows(ApiException.class,
                () -> support.approveIncident("INC-100")).status());
    }

    @Test
    void supportCheckRejectsInvalidReference() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> support.checkAccess(new SupportCheckRequest("BAD REF"))).status());
    }

    @Test
    void supportCheckIsDeniedByDefaultForInactiveMembershipOrMissingIncident() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        AccessDecision denied = support.checkAccess(new SupportCheckRequest("INC-100"));
        assertFalse(denied.allowed());
        assertEquals("NO_ACTIVE_MEMBERSHIP", denied.reason());

        membership("clinician");
        when(repository.activeIncident("INC-100", ACTOR, NOW)).thenReturn(Optional.empty());
        AccessDecision missing = support.checkAccess(new SupportCheckRequest("INC-100"));
        assertFalse(missing.allowed());
        assertEquals("NO_APPROVED_INCIDENT", missing.reason());
        verify(repository, org.mockito.Mockito.atLeastOnce()).appendAudit(any(), eq("SUPPORT_ACCESS"),
                eq("SUPPORT_ACCESS_CHECK"), eq("platform_support:INC-100"),
                eq("SUPPORT_ACCESS_INCIDENT"), eq("INC-100"), eq("DENIED"));
    }

    @Test
    void supportCheckRejectsSelfApprovedIncident() {
        bind("admin-a");
        membership("tenant_admin");
        when(repository.activeIncident("INC-100", "admin-a", NOW)).thenReturn(Optional.of(incident("admin-a")));
        AccessDecision decision = support.checkAccess(new SupportCheckRequest("INC-100"));
        assertFalse(decision.allowed());
        assertEquals("SELF_APPROVAL", decision.reason());
    }

    @Test
    void supportCheckAllowsSeparatelyApprovedIncident() {
        bind("admin-a");
        membership("tenant_admin");
        when(repository.activeIncident("INC-100", "admin-a", NOW)).thenReturn(Optional.of(incident("auditor-a")));
        AccessDecision decision = support.checkAccess(new SupportCheckRequest("INC-100"));
        assertTrue(decision.allowed());
        assertEquals("APPROVED_INCIDENT", decision.reason());
        verify(repository).appendAudit(any(), eq("SUPPORT_ACCESS"), eq("SUPPORT_ACCESS_CHECK"),
                eq("platform_support:INC-100"), eq("SUPPORT_ACCESS_INCIDENT"), eq("INC-100"), eq("SUCCESS"));
    }

    // --- Controller adapter ---

    @Test
    void controllerRoutesCommandsToTheDomainServices() {
        EmergencyAccessService emergencyMock = mock(EmergencyAccessService.class);
        SupportAccessService supportMock = mock(SupportAccessService.class);
        AccessController controller = new AccessController(emergencyMock, supportMock);
        EmergencyGrantRequest grantRequest = validGrant();
        RevokeRequest revokeRequest = new RevokeRequest("done");
        ReviewRequest reviewRequest = new ReviewRequest("checked");
        SupportIncidentRequest incidentRequest = validIncident();
        SupportCheckRequest checkRequest = new SupportCheckRequest("INC-100");
        EmergencyGrantView grantView = new EmergencyGrantView("grant-1", ACTOR,
                "MAIN", "clinician", "why", "2026-11-19T10:00:00Z", "2026-11-19T10:10:00Z", "active");
        SupportIncidentView incidentView = new SupportIncidentView("INC-100", "admin-a", "why",
                "pending", "2026-11-19T10:00:00Z", null, null, "2026-11-19T11:00:00Z");
        AccessDecision decision = new AccessDecision(true, "ACTIVE");

        when(emergencyMock.grant(grantRequest)).thenReturn(grantView);
        when(emergencyMock.active()).thenReturn(decision);
        when(emergencyMock.revoke("grant-1", revokeRequest)).thenReturn(grantView);
        when(emergencyMock.review("grant-1", reviewRequest)).thenReturn(grantView);
        when(emergencyMock.reviewQueue()).thenReturn(List.of(grantView));
        when(supportMock.createIncident(incidentRequest)).thenReturn(incidentView);
        when(supportMock.approveIncident("INC-100")).thenReturn(incidentView);
        when(supportMock.checkAccess(checkRequest)).thenReturn(decision);

        ResponseEntity<EmergencyGrantView> grantResponse = controller.grant(grantRequest);
        assertEquals(201, grantResponse.getStatusCode().value());
        assertEquals(grantView, grantResponse.getBody());
        assertEquals(decision, controller.active());
        assertEquals(grantView, controller.revoke("grant-1", revokeRequest));
        assertEquals(grantView, controller.review("grant-1", reviewRequest));
        assertEquals(List.of(grantView), controller.reviewQueue());
        ResponseEntity<SupportIncidentView> incidentResponse = controller.createIncident(incidentRequest);
        assertEquals(201, incidentResponse.getStatusCode().value());
        assertEquals(incidentView, incidentResponse.getBody());
        assertEquals(incidentView, controller.approveIncident("INC-100"));
        assertEquals(decision, controller.checkAccess(checkRequest));
        verify(emergencyMock).grant(grantRequest);
        verify(emergencyMock).active();
        verify(emergencyMock).revoke("grant-1", revokeRequest);
        verify(emergencyMock).review("grant-1", reviewRequest);
        verify(emergencyMock).reviewQueue();
        verify(supportMock).createIncident(incidentRequest);
        verify(supportMock).approveIncident("INC-100");
        verify(supportMock).checkAccess(checkRequest);
    }

    @Test
    void accessRulesBoundariesAreEnforced() {
        AccessRules.validateReason("x", "E");
        AccessRules.validateReason("a".repeat(AccessRules.MAX_REASON_LENGTH), "E");
        assertThrows(ApiException.class, () -> AccessRules.validateReason(null, "E"));
        assertThrows(ApiException.class, () -> AccessRules.validateReason("   ", "E"));
        assertThrows(ApiException.class, () -> AccessRules.validateReason("a".repeat(AccessRules.MAX_REASON_LENGTH + 1), "E"));

        AccessRules.validateDuration(1);
        AccessRules.validateDuration(AccessRules.MAX_DURATION_MINUTES);
        assertThrows(ApiException.class, () -> AccessRules.validateDuration(0));
        assertThrows(ApiException.class, () -> AccessRules.validateDuration(AccessRules.MAX_DURATION_MINUTES + 1));

        AccessRules.validateWindow(1);
        AccessRules.validateWindow(AccessRules.MAX_WINDOW_MINUTES);
        assertThrows(ApiException.class, () -> AccessRules.validateWindow(0));
        assertThrows(ApiException.class, () -> AccessRules.validateWindow(AccessRules.MAX_WINDOW_MINUTES + 1));
    }
}
