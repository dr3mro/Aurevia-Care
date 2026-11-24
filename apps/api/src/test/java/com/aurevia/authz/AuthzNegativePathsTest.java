package com.aurevia.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.aurevia.authz.access.AccessContracts.EmergencyGrantRequest;
import com.aurevia.authz.access.AccessContracts.GrantRow;
import com.aurevia.authz.access.AccessContracts.ReviewRequest;
import com.aurevia.authz.access.AccessContracts.RevokeRequest;
import com.aurevia.authz.access.AccessContracts.SupportIncidentRequest;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.authz.access.EmergencyAccessService;
import com.aurevia.authz.access.SupportAccessService;
import com.aurevia.authz.history.RoleHistoryService;
import com.aurevia.authz.history.RoleHistoryStore;
import com.aurevia.authz.specialty.SpecialtyAttestationService;
import com.aurevia.authz.specialty.SpecialtyContract.AttestationRequest;
import com.aurevia.authz.specialty.SpecialtyController;
import com.aurevia.tenant.TenantContext;

/**
 * Negative-path and tenant-isolation assertions for the authorization domain.
 *
 * <p>Every scenario drives a real Spring service or controller with a mocked RLS
 * repository: a request that is missing, malformed or over long is refused before
 * SQL; a caller without the qualifying role is denied and audited; an identifier
 * owned by another tenant yields a generic not-found answer that reveals none of
 * the foreign record; a missing or malformed version precondition stops the
 * command; and a repeated command cannot stack a second effect. Only synthetic
 * identifiers, subjects and reasons appear in these fixtures.</p>
 */
class AuthzNegativePathsTest {

    private static final String ORGANIZATION = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-21T09:00:00Z");
    private static final String FOREIGN_GRANT = "foreign-grant";
    private static final String FOREIGN_SUBJECT = "foreign-clinician";
    private static final String FOREIGN_FACILITY = "FOREIGN-FACILITY";

    private AccessRepository access;
    private RoleHistoryStore historyStore;
    private SpecialtyAttestationService specialtyService;
    private TransactionTemplate transactions;
    private EmergencyAccessService emergency;
    private SupportAccessService support;
    private RoleHistoryService roleHistory;
    private SpecialtyController specialtyController;

    /**
     * Wire the services against bounded mocked stores and a synchronous transaction.
     */
    @BeforeEach
    void setUp() {
        access = mock(AccessRepository.class);
        historyStore = mock(RoleHistoryStore.class);
        specialtyService = mock(SpecialtyAttestationService.class);
        transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        emergency = new EmergencyAccessService(access, transactions, clock);
        support = new SupportAccessService(access, transactions, clock);
        roleHistory = new RoleHistoryService(historyStore, access, new ActionPolicy(), transactions);
        specialtyController = new SpecialtyController(specialtyService);
        bind("clinician-a");
    }

    /**
     * Remove the thread-bound tenant context after each scenario.
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORGANIZATION, subject, "synthetic", "negative-path-corr"));
    }

    private void roles(String... values) {
        when(access.hasActiveMembership(anyString())).thenReturn(true);
        when(access.activeRoles(anyString())).thenReturn(List.of(values));
    }

    private GrantRow activeGrant() {
        return new GrantRow("grant-1", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW, NOW.plusSeconds(600), null, null, null, null);
    }

    @Test
    void missingMalformedAndOversizedInputFailsClosedBeforePersistence() {
        ApiException noFacility = assertThrows(ApiException.class,
                () -> emergency.grant(new EmergencyGrantRequest(null, "why", 10)));
        assertEquals(422, noFacility.status());
        assertEquals("INVALID_FACILITY", noFacility.code());

        ApiException overlongReason = assertThrows(ApiException.class,
                () -> emergency.grant(new EmergencyGrantRequest("MAIN", "x".repeat(501), 10)));
        assertEquals(422, overlongReason.status());
        assertEquals("INVALID_REASON", overlongReason.code());

        ApiException badDuration = assertThrows(ApiException.class,
                () -> emergency.grant(new EmergencyGrantRequest("MAIN", "why", 61)));
        assertEquals(422, badDuration.status());
        assertEquals("INVALID_DURATION", badDuration.code());

        ApiException shortReference = assertThrows(ApiException.class,
                () -> support.createIncident(new SupportIncidentRequest("ab", "why", 60)));
        assertEquals(422, shortReference.status());
        assertEquals("INVALID_REFERENCE", shortReference.code());

        ApiException badWindow = assertThrows(ApiException.class,
                () -> support.createIncident(new SupportIncidentRequest("INC-1", "why", 241)));
        assertEquals(422, badWindow.status());
        assertEquals("INVALID_WINDOW", badWindow.code());

        ApiException badLimit = assertThrows(ApiException.class, () -> specialtyController.list("abc"));
        assertEquals(422, badLimit.status());
        assertEquals("INVALID_LIMIT", badLimit.code());

        verifyNoInteractions(access, historyStore);
        verify(specialtyService, never()).list(anyInt());
    }

    @Test
    void wrongRoleIsDeniedAndAuditedForProtectedCommands() {
        roles("reception");

        ApiException noGrant = assertThrows(ApiException.class,
                () -> emergency.grant(new EmergencyGrantRequest("MAIN", "why", 10)));
        assertEquals(403, noGrant.status());
        assertEquals("FORBIDDEN", noGrant.code());

        ApiException noApproval = assertThrows(ApiException.class, () -> support.approveIncident("INC-1"));
        assertEquals(403, noApproval.status());
        assertEquals("FORBIDDEN", noApproval.code());

        ApiException noReview = assertThrows(ApiException.class,
                () -> emergency.review("grant-1", new ReviewRequest("checked")));
        assertEquals(403, noReview.status());
        assertEquals("FORBIDDEN", noReview.code());

        ApiException noHistory = assertThrows(ApiException.class, () -> roleHistory.history("staff-a"));
        assertEquals(403, noHistory.status());
        assertEquals("FORBIDDEN", noHistory.code());

        verify(access, atLeastOnce()).appendAudit(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("DENIED"));
    }

    @Test
    void identifierOwnedByAnotherTenantLooksLikeNotFoundAndDoesNotLeakTheRecord() {
        roles("clinician");
        when(access.revokeGrant(FOREIGN_GRANT, "stop", NOW)).thenReturn(0);

        ApiException missingGrant = assertThrows(ApiException.class,
                () -> emergency.revoke(FOREIGN_GRANT, new RevokeRequest("stop")));
        assertEquals(404, missingGrant.status());
        assertEquals("NOT_FOUND", missingGrant.code());
        assertFalse(missingGrant.getMessage().contains(FOREIGN_SUBJECT));
        assertFalse(missingGrant.getMessage().contains(FOREIGN_FACILITY));
        assertFalse(missingGrant.getMessage().contains(FOREIGN_GRANT));
        assertEquals("emergency grant not found or already revoked", missingGrant.getMessage());
        // The foreign identifier must not become a grant or a reviewed grant: the
        // only durable effect is the NOT_FOUND audit row.
        verify(access, never()).findGrant(FOREIGN_GRANT);
        verify(access, never()).insertGrant(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        verify(access, never()).reviewGrant(anyString(), anyString(), anyString(), any());
        verify(access).appendAudit(any(), anyString(), anyString(), anyString(), anyString(),
                eq(FOREIGN_GRANT), eq("NOT_FOUND"));

        bind("admin-a");
        roles("tenant_admin");
        when(historyStore.findBySubject("other-staff")).thenReturn(List.of());
        ApiException missingHistory = assertThrows(ApiException.class,
                () -> roleHistory.history("other-staff"));
        assertEquals(404, missingHistory.status());
        assertEquals("NOT_FOUND", missingHistory.code());
        assertEquals("staff history not found", missingHistory.getMessage());
        verify(historyStore).findBySubject("other-staff");
        verify(access).appendAudit(any(), anyString(), anyString(), anyString(), anyString(),
                eq("other-staff"), eq("NOT_FOUND"));
    }

    @Test
    void missingOrMalformedVersionPreconditionIsRejectedBeforeTheCommand() {
        AttestationRequest request = new AttestationRequest("SPECIALTY_LAB_VERIFY_RELEASE", "lab_verifier",
                ORGANIZATION, "ORD-1", "synthetic clinical decision");

        ApiException missing = assertThrows(ApiException.class,
                () -> specialtyController.attest(request, null));
        assertEquals(428, missing.status());
        assertEquals("PRECONDITION_REQUIRED", missing.code());

        ApiException zero = assertThrows(ApiException.class, () -> specialtyController.attest(request, "0"));
        assertEquals(422, zero.status());
        assertEquals("INVALID_IF_MATCH", zero.code());

        ApiException malformed = assertThrows(ApiException.class,
                () -> specialtyController.attest(request, "not-a-version"));
        assertEquals(422, malformed.status());
        assertEquals("INVALID_IF_MATCH", malformed.code());

        verifyNoInteractions(specialtyService);
    }

    @Test
    void repeatedCommandCannotStackASecondGrant() {
        roles("clinician");
        when(access.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(access.facilityExists("MAIN")).thenReturn(true);
        when(access.latestGrant("clinician-a")).thenReturn(Optional.of(activeGrant()));

        ApiException conflict = assertThrows(ApiException.class,
                () -> emergency.grant(new EmergencyGrantRequest("MAIN", "why", 10)));
        assertEquals(409, conflict.status());
        assertEquals("CONFLICT", conflict.code());
        verify(access, never()).insertGrant(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        verify(access).appendAudit(any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                eq("CONFLICT"));
    }
}
