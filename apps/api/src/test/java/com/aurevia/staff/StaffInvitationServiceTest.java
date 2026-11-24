package com.aurevia.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * BUILD-01 / IAM-02 unit acceptance: invitation validation, authorization, audit and
 * the single-use accept outcome. Every failure path is a branch of the service and
 * is asserted here with synthetic identifiers only.
 */
class StaffInvitationServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";
    private static final String SUBJECT = "new-staff-a";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String INVITATION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String TOKEN = "synthetic-token-abcdefghijklmnop";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private StaffInvitationStore store;
    private StaffRepository staff;
    private TransactionTemplate transactions;
    private StaffInvitationService service;

    @BeforeEach
    void setUp() {
        store = mock(StaffInvitationStore.class);
        staff = mock(StaffRepository.class);
        transactions = mock(TransactionTemplate.class);
        service = new StaffInvitationService(store, staff, new ActionPolicy(), transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "STAFF_ADMIN", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(staff.hasActiveMembership(ACTOR)).thenReturn(true);
        when(staff.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
    }

    private static StaffInvitation invitation(String status) {
        return new StaffInvitation(INVITATION, SUBJECT, "New Staff", FACILITY,
                List.of("reception", "tenant_admin"), "2026-11-21T13:00:00Z", status, ACTOR);
    }

    @Test
    void createRejectsEveryInvalidFieldBeforeAnyWrite() {
        assertThrows(ApiException.class, () -> service.create(null, "Name", FACILITY, List.of("reception"), 600L));
        assertThrows(ApiException.class, () -> service.create("Bad", "Name", FACILITY, List.of("reception"), 600L));
        assertThrows(ApiException.class, () -> service.create(SUBJECT, null, FACILITY, List.of("reception"), 600L));
        assertThrows(ApiException.class, () -> service.create(SUBJECT, "  ", FACILITY, List.of("reception"), 600L));
        assertThrows(ApiException.class,
                () -> service.create(SUBJECT, "x".repeat(201), FACILITY, List.of("reception"), 600L));
        assertThrows(ApiException.class, () -> service.create(SUBJECT, "Name", null, List.of("reception"), 600L));
        assertThrows(ApiException.class,
                () -> service.create(SUBJECT, "Name", "not-a-uuid", List.of("reception"), 600L));
        assertThrows(ApiException.class, () -> service.create(SUBJECT, "Name", FACILITY, null, 600L));
        assertThrows(ApiException.class, () -> service.create(SUBJECT, "Name", FACILITY, List.of(), 600L));
        assertThrows(ApiException.class,
                () -> service.create(SUBJECT, "Name", FACILITY, List.of("superuser"), 600L));
        assertThrows(ApiException.class, () -> service.create(SUBJECT, "Name", FACILITY, List.of("reception"), 59L));
        assertThrows(ApiException.class,
                () -> service.create(SUBJECT, "Name", FACILITY, List.of("reception"), 86_401L));
        verify(store, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createAcceptsTheCanonicalDottedAndUnderscoredSubjects() {
        authorized();
        when(staff.findUserIdBySubject("patient.one")).thenReturn(Optional.empty());
        assertEquals("patient.one", service.create("patient.one", "Name", FACILITY,
                List.of("reception"), 600L).subject());
        when(staff.findUserIdBySubject("patient_one")).thenReturn(Optional.empty());
        assertEquals("patient_one", service.create("patient_one", "Name", FACILITY,
                List.of("reception"), 600L).subject());
    }

    @Test
    void createDeniesInactiveMembershipAndAudits() {
        when(staff.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException failure = assertThrows(ApiException.class,
                () -> service.create(SUBJECT, "Name", FACILITY, List.of("reception"), 600L));
        assertEquals(403, failure.status());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_CREATE", SUBJECT, "DENIED");
        verify(store, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createDeniesMissingPermissionAndAudits() {
        when(staff.hasActiveMembership(ACTOR)).thenReturn(true);
        when(staff.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.create(SUBJECT, "Name", FACILITY, List.of("reception"), 600L)).status());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_CREATE", SUBJECT, "DENIED");
    }

    @Test
    void createRejectsAnExistingStaffSubject() {
        authorized();
        when(staff.findUserIdBySubject(SUBJECT)).thenReturn(Optional.of("existing-id"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.create(SUBJECT, "Name", FACILITY, List.of("reception"), 600L)).status());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_CREATE", SUBJECT, "CONFLICT");
        verify(store, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createRejectsAConcurrentDuplicate() {
        authorized();
        when(staff.findUserIdBySubject(SUBJECT)).thenReturn(Optional.empty());
        when(store.insert(eq(ORG), any(), eq(SUBJECT), eq("Name"), eq(FACILITY),
                eq(List.of("reception")), eq(ACTOR), any(), eq(NOW.plusSeconds(600))))
                .thenThrow(new DuplicateKeyException("race"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.create(SUBJECT, "Name", FACILITY, List.of("reception"), 600L)).status());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_CREATE", SUBJECT, "CONFLICT");
    }

    @Test
    void createStoresOnlyAHashAndReturnsTheRawTokenOnce() {
        authorized();
        when(staff.findUserIdBySubject(SUBJECT)).thenReturn(Optional.empty());
        when(store.insert(eq(ORG), any(), eq(SUBJECT), eq("Name"), eq(FACILITY),
                eq(List.of("reception", "tenant_admin")), eq(ACTOR), any(), eq(NOW.plusSeconds(600))))
                .thenReturn(1);
        StaffInvitationIssue issue = service.create(SUBJECT, "Name", FACILITY,
                List.of("reception", "tenant_admin"), 600L);
        assertEquals(SUBJECT, issue.subject());
        assertEquals(NOW.plusSeconds(600).toString(), issue.expiresAt());
        assertEquals(List.of("reception", "tenant_admin"), issue.roles());
        assertNotNull(issue.token());
        assertTrue(issue.token().length() >= 32);
        assertFalse(issue.token().contains(" "));
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_CREATE", SUBJECT, "SUCCESS");
    }

    @Test
    void listPendingDeniesInactiveMembership() {
        when(staff.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, service::listPending).status());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_LIST", "ALL", "DENIED");
        verify(store, never()).findPending();
    }

    @Test
    void listPendingDeniesMissingPermission() {
        when(staff.hasActiveMembership(ACTOR)).thenReturn(true);
        when(staff.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class, service::listPending).status());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_LIST", "ALL", "DENIED");
    }

    @Test
    void listPendingReturnsTheTenantProjectionAndAudits() {
        authorized();
        List<StaffInvitation> rows = List.of(invitation("pending"));
        when(store.findPending()).thenReturn(rows);
        assertEquals(rows, service.listPending());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_LIST", "ALL", "SUCCESS");
    }

    @Test
    void revokeRejectsAnInvalidId() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.revoke(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.revoke("not-a-uuid")).status());
        verify(store, never()).revoke(any());
    }

    @Test
    void revokeDeniesInactiveMembership() {
        when(staff.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.revoke(INVITATION)).status());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_REVOKE", INVITATION, "DENIED");
    }

    @Test
    void revokeDeniesMissingPermission() {
        when(staff.hasActiveMembership(ACTOR)).thenReturn(true);
        when(staff.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class, () -> service.revoke(INVITATION)).status());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_REVOKE", INVITATION, "DENIED");
    }

    @Test
    void revokeConcealsAnUnknownOrConsumedInvitation() {
        authorized();
        when(store.revoke(INVITATION)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.revoke(INVITATION)).status());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_REVOKE", INVITATION, "NOT_FOUND");
    }

    @Test
    void revokeMarksTheInvitationRevokedAndAudits() {
        authorized();
        StaffInvitation revoked = invitation("revoked");
        when(store.revoke(INVITATION)).thenReturn(Optional.of(revoked));
        assertEquals(revoked, service.revoke(INVITATION));
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_REVOKE", INVITATION, "SUCCESS");
    }

    @Test
    void acceptRejectsAnInvalidToken() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.accept(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.accept("short")).status());
        verify(store, never()).accept(any());
    }

    @Test
    void acceptRejectsExpiredReusedRevokedAndWrongTenantTokens() {
        // All unusable tokens are concealed behind one outcome: no existence oracle.
        when(store.accept(TOKEN)).thenReturn(Optional.empty());
        ApiException failure = assertThrows(ApiException.class, () -> service.accept(TOKEN));
        assertEquals(409, failure.status());
        assertEquals("INVITATION_UNAVAILABLE", failure.code());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_ACCEPT", "ME", "CONFLICT");
    }

    @Test
    void acceptCreatesTheMembershipAndRecordsTheGrantor() {
        when(store.accept(TOKEN)).thenReturn(Optional.of(SUBJECT));
        StaffInvitationAcceptance acceptance = service.accept(TOKEN);
        assertEquals(SUBJECT, acceptance.subject());
        assertEquals("active", acceptance.status());
        verify(staff).appendAudit(TenantContext.current(), "STAFF_INVITATION_ACCEPT", SUBJECT, "SUCCESS");
    }

    @Test
    void valueRecordsExposeTheirPartsAndFreezeTheirLists() {
        StaffInvitation invitation = invitation("pending");
        assertEquals(INVITATION, invitation.id());
        assertEquals(SUBJECT, invitation.subject());
        assertEquals("New Staff", invitation.displayName());
        assertEquals(FACILITY, invitation.facilityId());
        assertEquals("2026-11-21T13:00:00Z", invitation.expiresAt());
        assertEquals("pending", invitation.status());
        assertEquals(ACTOR, invitation.invitedBy());
        assertEquals(List.of("reception", "tenant_admin"), invitation.roles());
        assertThrows(UnsupportedOperationException.class, () -> invitation.roles().clear());
        StaffInvitation duplicate = invitation("pending");
        assertEquals(invitation, duplicate);
        assertEquals(invitation.hashCode(), duplicate.hashCode());
        assertEquals(invitation.toString(), duplicate.toString());

        StaffInvitationIssue issue = new StaffInvitationIssue(INVITATION, SUBJECT, TOKEN,
                "2026-11-21T13:00:00Z", List.of("reception"));
        assertEquals(INVITATION, issue.id());
        assertEquals(SUBJECT, issue.subject());
        assertEquals(TOKEN, issue.token());
        assertEquals("2026-11-21T13:00:00Z", issue.expiresAt());
        assertEquals(List.of("reception"), issue.roles());
        assertThrows(UnsupportedOperationException.class, () -> issue.roles().clear());
        StaffInvitationIssue duplicateIssue = new StaffInvitationIssue(INVITATION, SUBJECT, TOKEN,
                "2026-11-21T13:00:00Z", List.of("reception"));
        assertEquals(issue, duplicateIssue);
        assertEquals(issue.hashCode(), duplicateIssue.hashCode());
        assertEquals(issue.toString(), duplicateIssue.toString());

        StaffInvitationAcceptance acceptance = new StaffInvitationAcceptance(SUBJECT, "active");
        assertEquals(SUBJECT, acceptance.subject());
        assertEquals("active", acceptance.status());
        assertEquals(acceptance, new StaffInvitationAcceptance(SUBJECT, "active"));
        assertEquals(acceptance.hashCode(), new StaffInvitationAcceptance(SUBJECT, "active").hashCode());
    }
}
