package com.aurevia.authz.access;

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
import com.aurevia.authz.access.AccessContracts.ReviewRequest;
import com.aurevia.tenant.TenantContext;

/**
 * IAM-06 regression tests for the access-review slice: a grant must create a
 * privacy-auditor alert and an open review at once, a review must close with an
 * explicit outcome, expiry is decided on the next request and tenant scope is
 * always taken from the authenticated context. All fixtures are synthetic.
 */
class EmergencyAccessReviewTest {

    private static final String ORGANIZATION = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-21T09:00:00Z");

    private AccessRepository repository;
    private EmergencyAccessService emergency;

    /** Wire the service against a mocked repository and a synchronous transaction. */
    @BeforeEach
    void setUp() {
        repository = mock(AccessRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        emergency = new EmergencyAccessService(repository, transactions, clock);
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
        TenantContext.set(new TenantContext(ORGANIZATION, subject, "EMERGENCY_ACCESS", "review-corr"));
    }

    private void roles(String... values) {
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        when(repository.activeRoles(anyString())).thenReturn(List.of(values));
    }

    private GrantRow grantRow() {
        return new GrantRow("grant-1", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "patient in distress",
                "clinician", NOW, NOW.plusSeconds(600), null, null, null, null);
    }

    private EmergencyGrantRequest grantRequest() {
        return new EmergencyGrantRequest("MAIN", "patient in distress", 10);
    }

    private void approvableGrant() {
        roles("clinician");
        when(repository.effectiveConfig("access.emergency.enabled")).thenReturn("true");
        when(repository.facilityExists("MAIN")).thenReturn(true);
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.empty());
        when(repository.insertGrant(ORGANIZATION, "clinician-a", "MAIN", "EMERGENCY_ACCESS",
                "patient in distress", "clinician", NOW, NOW.plusSeconds(600))).thenReturn(grantRow());
    }

    @Test
    void grantCreatesAnAlertAndAnOpenReviewForThePrivacyAuditor() {
        approvableGrant();

        EmergencyGrantView view = emergency.grant(grantRequest());

        assertEquals("active", view.status());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_ALERT"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("SUCCESS"));
        verify(repository).openReview(ORGANIZATION, "grant-1", "clinician-a", NOW);
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_REVIEW_OPEN"),
                eq("privacy_auditor:MAIN"), eq("EMERGENCY_ACCESS_REVIEW"), eq("grant-1"), eq("SUCCESS"));
    }

    @Test
    void reviewClosesTheOpenReviewWithAnExplicitOutcome() {
        bind("auditor-a");
        roles("privacy_auditor");
        when(repository.reviewGrant("grant-1", "auditor-a", "checked", NOW)).thenReturn(1);
        GrantRow reviewed = new GrantRow("grant-1", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW, NOW.plusSeconds(600), null, "auditor-a", NOW, "checked");
        when(repository.findGrant("grant-1")).thenReturn(Optional.of(reviewed));

        assertEquals("reviewed", emergency.review("grant-1", new ReviewRequest("checked", "concerns_noted")).status());

        verify(repository).closeReview("grant-1", "auditor-a", "concerns_noted", "checked", NOW);
    }

    @Test
    void reviewWithoutAnExplicitOutcomeIsClosedWithARecordedOutcome() {
        bind("auditor-a");
        roles("privacy_auditor");
        when(repository.reviewGrant("grant-1", "auditor-a", "checked", NOW)).thenReturn(1);
        when(repository.findGrant("grant-1")).thenReturn(Optional.of(new GrantRow("grant-1", "clinician-a",
                "MAIN", "EMERGENCY_ACCESS", "why", "clinician", NOW, NOW.plusSeconds(600), null,
                "auditor-a", NOW, "checked")));

        assertEquals("reviewed", emergency.review("grant-1", new ReviewRequest("checked")).status());

        verify(repository).closeReview("grant-1", "auditor-a", "recorded", "checked", NOW);
    }

    @Test
    void reviewRejectsAnUnknownOutcomeBeforeAnyDatabaseWork() {
        bind("auditor-a");
        roles("privacy_auditor");

        ApiException failure = assertThrows(ApiException.class,
                () -> emergency.review("grant-1", new ReviewRequest("checked", "not-an-outcome")));

        assertEquals(422, failure.status());
        assertEquals("INVALID_OUTCOME", failure.code());
        verify(repository, never()).reviewGrant(anyString(), anyString(), anyString(), any());
    }

    @Test
    void expiredGrantIsDeniedOnTheNextRequest() {
        roles("clinician");
        GrantRow expired = new GrantRow("grant-1", "clinician-a", "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW.minusSeconds(1200), NOW.minusSeconds(60), null, null, null, null);
        when(repository.latestGrant("clinician-a")).thenReturn(Optional.of(expired));

        assertEquals(new AccessDecision(false, "EXPIRED"), emergency.active());
        verify(repository).appendAudit(any(), eq("EMERGENCY_ACCESS"), eq("EMERGENCY_ACTIVE_CHECK"),
                eq("clinician:MAIN"), eq("EMERGENCY_ACCESS_GRANT"), eq("grant-1"), eq("DENIED"));
    }

    @Test
    void missingReasonIsRefusedBeforeAnyDatabaseWork() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> emergency.grant(new EmergencyGrantRequest("MAIN", "  ", 10))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> emergency.grant(new EmergencyGrantRequest("MAIN", null, 10))).status());
        verify(repository, never()).insertGrant(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any());
        verify(repository, never()).openReview(anyString(), anyString(), anyString(), any());
    }

    @Test
    void reviewQueueListsUnreviewedGrantsForAReviewer() {
        roles("privacy_auditor");
        GrantRow reviewed = new GrantRow("done", "clinician-b", "MAIN", "EMERGENCY_ACCESS", "why",
                "clinician", NOW.minusSeconds(60), NOW.plusSeconds(60), null, "auditor-a",
                NOW, "checked");
        when(repository.listGrants()).thenReturn(List.of(grantRow(), reviewed));

        List<EmergencyGrantView> queue = emergency.reviewQueue();

        assertEquals(2, queue.size());
        assertEquals("grant-1", queue.get(0).id());
        assertEquals("active", queue.get(0).status());
        assertEquals("reviewed", queue.get(1).status());
        assertTrue(queue.stream().anyMatch(row -> "active".equals(row.status())));
    }

    @Test
    void grantNeverUsesAnOrganizationOrActorFromTheCommand() {
        approvableGrant();

        emergency.grant(grantRequest());

        verify(repository).insertGrant(eq(ORGANIZATION), eq("clinician-a"), anyString(), anyString(),
                anyString(), anyString(), any(), any());
        verify(repository).openReview(eq(ORGANIZATION), eq("grant-1"), eq("clinician-a"), eq(NOW));
        verify(repository).facilityExists("MAIN");
        assertFalse(grantRequest().toString().contains(ORGANIZATION));
    }
}
