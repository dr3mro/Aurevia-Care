package com.aurevia.it.patient.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
import com.aurevia.tenant.TenantContext;

/**
 * IAM-05 regression: an unlink or re-verification must invalidate invitations
 * that were issued for the same account and chart but never redeemed.
 *
 * <p>Without this, a dispute could be silently reversed by redeeming an old
 * token, bypassing the fresh verification that re-verification is meant to
 * require. All fixtures are synthetic.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutstandingInvitationRevocationTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String STAFF = "admin-a";

    @Mock
    private IdentityProofingRepository repository;
    @Mock
    private IdentityActionPolicy policy;

    private IdentityProofingService service;

    @BeforeEach
    void setUp() {
        service = new IdentityProofingService(repository, policy,
                new TransactionTemplate(new PassthroughTransactionManager()));
        TenantContext.set(new TenantContext(ORG, STAFF, "PATIENT_IDENTITY", "corr-revoke"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Runs callbacks directly so no database is needed for service-logic evidence. */
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

    @Test
    void unlinkRevokesOutstandingInvitationsForTheAffectedChart() {
        staffAllowed(IdentityActionPolicy.LINK_UNLINK);
        when(repository.currentLink("patient.one", CHART)).thenReturn(
                Optional.of(link("linked", "INVITATION", null)),
                Optional.of(link("unlinked", "STAFF_ACTION", "dispute")));

        service.unlink("patient.one", CHART, "dispute");

        verify(repository).consumeOutstandingInvitations("patient.one", CHART);
    }

    @Test
    void reverifyRevokesOldInvitationsBeforeIssuingTheFreshOne() {
        staffAllowed(IdentityActionPolicy.LINK_REVERIFY);
        when(repository.accountExists("patient.one")).thenReturn(true);
        when(repository.chartExists(CHART)).thenReturn(true);
        when(repository.currentLink("patient.one", CHART))
                .thenReturn(Optional.of(link("linked", "INVITATION", null)));

        InvitationIssue issued = service.reverify("patient.one", CHART, "lost access");

        assertNotNull(issued.token());
        InOrder order = inOrder(repository);
        order.verify(repository).consumeOutstandingInvitations("patient.one", CHART);
        order.verify(repository).insertInvitation(eq(ORG), anyString(), eq("patient.one"), eq(CHART),
                eq("REVERIFY"), anyString(), eq(600L));
    }

    @Test
    void patientInitiatedUnlinkAlsoRevokesOutstandingInvitations() {
        TenantContext.set(new TenantContext(ORG, "patient.one", "PATIENT_IDENTITY", "corr-self-revoke"));
        when(repository.accountExists("patient.one")).thenReturn(true);
        when(repository.currentLink("patient.one", CHART)).thenReturn(
                Optional.of(link("linked", "INVITATION", null)),
                Optional.of(link("unlinked", "PATIENT_REQUEST", "no longer needed")));

        service.unlink("patient.one", CHART, "no longer needed");

        verify(repository).consumeOutstandingInvitations("patient.one", CHART);
    }

    @Test
    void unlinkWithoutACurrentLinkStillRevokesOutstandingInvitations() {
        staffAllowed(IdentityActionPolicy.LINK_UNLINK);
        when(repository.consumeOutstandingInvitations("patient.one", CHART)).thenReturn(2);
        when(repository.currentLink("patient.one", CHART)).thenReturn(Optional.empty());

        ApiException denial = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.unlink("patient.one", CHART, "dispute"));

        assertEquals(404, denial.status());
        verify(repository).consumeOutstandingInvitations("patient.one", CHART);
        verify(repository).appendAudit(any(), eq("INVITATION_RETIRE"), eq(CHART), eq("SUCCESS"));
        verify(repository, never()).insertLink(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reverifyWithoutACurrentLinkStillRevokesOutstandingInvitations() {
        staffAllowed(IdentityActionPolicy.LINK_REVERIFY);
        when(repository.accountExists("patient.one")).thenReturn(true);
        when(repository.chartExists(CHART)).thenReturn(true);
        when(repository.consumeOutstandingInvitations("patient.one", CHART)).thenReturn(1);
        when(repository.currentLink("patient.one", CHART)).thenReturn(Optional.empty());

        ApiException denial = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.reverify("patient.one", CHART, "lost access"));

        assertEquals(404, denial.status());
        verify(repository).consumeOutstandingInvitations("patient.one", CHART);
        verify(repository).appendAudit(any(), eq("INVITATION_RETIRE"), eq(CHART), eq("SUCCESS"));
        verify(repository, never()).insertInvitation(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void aDeniedUnlinkDoesNotRevokeOutstandingInvitations() {
        when(repository.hasActiveMembership(STAFF)).thenReturn(true);
        when(repository.activeRoles(STAFF)).thenReturn(List.of("reception"));
        when(policy.allows(IdentityActionPolicy.LINK_UNLINK, "reception")).thenReturn(false);

        ApiException denial = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.unlink("patient.one", CHART, "dispute"));
        assertEquals(403, denial.status());
        verify(repository, never()).consumeOutstandingInvitations(anyString(), anyString());
        verify(repository, never()).appendAudit(any(), eq("INVITATION_RETIRE"), any(), any());
    }

    @Test
    void aPatientWithoutAVerifiedAccountCannotRevokeInvitations() {
        TenantContext.set(new TenantContext(ORG, "patient.one", "PATIENT_IDENTITY", "corr-self-denied"));
        when(repository.accountExists("patient.one")).thenReturn(false);

        ApiException denial = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.unlink("patient.one", CHART, "dispute"));

        assertEquals(403, denial.status());
        verify(repository, never()).consumeOutstandingInvitations(anyString(), anyString());
        verify(repository, never()).appendAudit(any(), eq("INVITATION_RETIRE"), any(), any());
    }

    @Test
    void aDeniedReverifyDoesNotRevokeOutstandingInvitations() {
        when(repository.hasActiveMembership(STAFF)).thenReturn(true);
        when(repository.activeRoles(STAFF)).thenReturn(List.of("reception"));
        when(policy.allows(IdentityActionPolicy.LINK_REVERIFY, "reception")).thenReturn(false);

        ApiException denial = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.reverify("patient.one", CHART, "lost access"));
        assertEquals(403, denial.status());
        verify(repository, never()).consumeOutstandingInvitations(anyString(), anyString());
        verify(repository, never()).appendAudit(any(), eq("INVITATION_RETIRE"), any(), any());
    }

    private void staffAllowed(String action) {
        when(repository.hasActiveMembership(STAFF)).thenReturn(true);
        when(repository.activeRoles(STAFF)).thenReturn(List.of("tenant_admin"));
        when(policy.allows(action, "tenant_admin")).thenReturn(true);
    }

    private static ChartLink link(String event, String method, String reason) {
        return new ChartLink("link-id", "patient.one", CHART, event, method, reason, "2026-11-19T12:00:00Z");
    }
}
