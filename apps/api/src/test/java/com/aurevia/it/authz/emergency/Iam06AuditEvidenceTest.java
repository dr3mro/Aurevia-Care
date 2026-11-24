package com.aurevia.it.authz.emergency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.aurevia.authz.access.AccessContracts.EmergencyGrantRequest;
import com.aurevia.authz.access.AccessContracts.GrantRow;
import com.aurevia.authz.access.AccessContracts.SupportCheckRequest;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.authz.access.EmergencyAccessService;
import com.aurevia.authz.access.SupportAccessService;
import com.aurevia.tenant.TenantContext;

/**
 * Independently owned IAM-06 verification of the audit evidence that the
 * specification requires for every sensitive action: business purpose, role,
 * facility scope, acting user and correlation identifier (OPS-02, IAM-06).
 *
 * <p>The inherited scoped suite asserts action, scope and outcome but matches the
 * {@link TenantContext} argument with {@code any()}. This suite captures that
 * argument explicitly on the grant, denial and support-check paths, so a future
 * service change that drops or substitutes the authenticated context is caught
 * by a second owner rather than only by the database integration suite. All
 * fixtures are synthetic; no production code is duplicated or edited.</p>
 */
class Iam06AuditEvidenceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CORRELATION = "iam06-audit-corr";
    private static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");

    private AccessRepository repository;
    private EmergencyAccessService emergency;
    private SupportAccessService support;

    /** Bind a synthetic authenticated context and wire the two domain services. */
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
        assertEquals(ORG, context.organizationId());
        assertEquals(subject, context.subject());
        assertEquals("EMERGENCY_ACCESS", context.purpose());
        assertEquals(CORRELATION, context.correlationId());
    }

    private EmergencyGrantRequest grantRequest() {
        return new EmergencyGrantRequest("MAIN", "patient in distress", 10);
    }

    private GrantRow grantRow() {
        return new GrantRow("grant-1", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600), null, null, null, null);
    }

    @Test
    void grantAndAlertAuditsCarryTheAuthenticatedTenantContext() {
        when(repository.hasActiveMembership("clinician-a")).thenReturn(true);
        when(repository.activeRoles("clinician-a")).thenReturn(List.of("clinician"));
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(repository.facilityExists("MAIN")).thenReturn(true);
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.empty());
        when(repository.insertGrant(ORG, "clinician-a", "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600))).thenReturn(grantRow());

        emergency.grant(grantRequest());

        // The grant and its separate alert must both be bound to the same
        // authenticated context, not to anything supplied by the command body.
        ArgumentCaptor<TenantContext> contexts = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository, times(2)).appendAudit(contexts.capture(), eq("EMERGENCY_ACCESS"),
                anyString(), eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
        assertEquals(2, contexts.getAllValues().size());
        for (TenantContext context : contexts.getAllValues()) {
            assertContext(context, "clinician-a");
        }
    }

    @Test
    void deniedGrantStillAuditsWithTheActingContextAndNoGrantIsWritten() {
        when(repository.hasActiveMembership("clinician-a")).thenReturn(false);

        assertEquals(403, assertThrows(ApiException.class,
                () -> emergency.grant(grantRequest())).status());

        ArgumentCaptor<TenantContext> context = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(context.capture(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_GRANT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("MAIN"), eq("DENIED"));
        assertContext(context.getValue(), "clinician-a");
        verify(repository, never()).insertGrant(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any());
    }

    @Test
    void supportCheckDenialAuditsWithTheActingContext() {
        bind("admin-a");
        when(repository.hasActiveMembership("admin-a")).thenReturn(true);
        when(repository.activeIncident("INC-1", "admin-a", NOW)).thenReturn(Optional.empty());

        support.checkAccess(new SupportCheckRequest("INC-1"));

        ArgumentCaptor<TenantContext> context = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(context.capture(), eq("SUPPORT_ACCESS"), eq("SUPPORT_ACCESS_CHECK"),
                eq("platform_support:INC-1"), eq("SUPPORT_ACCESS_INCIDENT"), eq("INC-1"), eq("DENIED"));
        assertContext(context.getValue(), "admin-a");
    }
}
