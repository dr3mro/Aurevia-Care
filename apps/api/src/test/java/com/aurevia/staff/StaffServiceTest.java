package com.aurevia.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.aurevia.web.StaffController;

/** S-G0-02 service-level branches: validation, authorization, concealment and audit (IAM-01/IAM-02/OPS-02). */
class StaffServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";
    private static final String TARGET = "target-a";

    private StaffRepository repository;
    private TransactionTemplate transactions;
    private StaffService service;

    @BeforeEach
    void setUp() {
        repository = mock(StaffRepository.class);
        transactions = mock(TransactionTemplate.class);
        service = new StaffService(repository, new ActionPolicy(), transactions);
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
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
    }

    private void actorIdentity() {
        when(repository.findUserIdBySubject(ACTOR)).thenReturn(Optional.of("actor-id"));
    }

    private StaffMember member(String status) {
        return new StaffMember(TARGET, "Target", status, List.of("reception"));
    }

    @Test
    void rejectsNullSubject() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.invite(null, "Name", "reception")).status());
    }

    @Test
    void rejectsMalformedSubject() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.invite("Bad", "Name", "reception")).status());
    }

    @Test
    void acceptsTheCanonicalDottedAndUnderscoredSubjects() {
        authorized();
        actorIdentity();
        when(repository.findUserIdBySubject("patient.one")).thenReturn(Optional.empty());
        when(repository.findBySubject("patient.one")).thenReturn(Optional.of(
                new StaffMember("patient.one", "Name", "active", List.of("reception"))));
        assertEquals("patient.one", service.invite("patient.one", "Name", "reception").subject());
        when(repository.findUserIdBySubject("patient_one")).thenReturn(Optional.empty());
        when(repository.findBySubject("patient_one")).thenReturn(Optional.of(
                new StaffMember("patient_one", "Name", "active", List.of("reception"))));
        assertEquals("patient_one", service.invite("patient_one", "Name", "reception").subject());
    }

    @Test
    void rejectsLongSubject() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.invite("a".repeat(65), "Name", "reception")).status());
    }

    @Test
    void rejectsNullDisplayName() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.invite(TARGET, null, "reception")).status());
    }

    @Test
    void rejectsBlankDisplayName() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.invite(TARGET, "  ", "reception")).status());
    }

    @Test
    void rejectsLongDisplayName() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.invite(TARGET, "x".repeat(201), "reception")).status());
    }

    @Test
    void rejectsNullRole() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.invite(TARGET, "Name", null)).status());
    }

    @Test
    void rejectsUnboundedRole() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.invite(TARGET, "Name", "superuser")).status());
    }

    @Test
    void inviteDeniesInactiveMembershipAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.invite(TARGET, "Name", "reception")).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_INVITE", TARGET, "DENIED");
        verify(repository, never()).insertIdentity(any(), any(), any());
    }

    @Test
    void inviteDeniesMissingPermissionAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.invite(TARGET, "Name", "reception")).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_INVITE", TARGET, "DENIED");
    }

    @Test
    void inviteRejectsExistingSubject() {
        authorized();
        when(repository.findUserIdBySubject(TARGET)).thenReturn(Optional.of("existing"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.invite(TARGET, "Name", "reception")).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_INVITE", TARGET, "CONFLICT");
    }

    @Test
    void inviteRejectsConcurrentDuplicate() {
        authorized();
        actorIdentity();
        when(repository.insertIdentity(ORG, TARGET, "Name"))
                .thenThrow(new DuplicateKeyException("race"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.invite(TARGET, "Name", "reception")).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_INVITE", TARGET, "CONFLICT");
    }

    @Test
    void inviteDeniesWhenActorHasNoIdentity() {
        authorized();
        when(repository.findUserIdBySubject(TARGET)).thenReturn(Optional.empty());
        when(repository.findUserIdBySubject(ACTOR)).thenReturn(Optional.empty());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.invite(TARGET, "Name", "reception")).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_INVITE", TARGET, "DENIED");
    }

    @Test
    void inviteCreatesGrantsAndAudits() {
        authorized();
        actorIdentity();
        when(repository.findUserIdBySubject(TARGET)).thenReturn(Optional.empty());
        when(repository.insertIdentity(ORG, TARGET, "Name")).thenReturn("target-id");
        when(repository.insertMembership(ORG, "target-id", "active")).thenReturn("membership-id");
        when(repository.findBySubject(TARGET)).thenReturn(Optional.of(member("active")));
        assertEquals("active", service.invite(TARGET, "Name", "reception").status());
        verify(repository).insertRoleGrant(ORG, "membership-id", "reception", "actor-id");
        verify(repository).appendAudit(TenantContext.current(), "STAFF_INVITE", TARGET, "SUCCESS");
    }

    @Test
    void deactivateDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.deactivate(TARGET)).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_DEACTIVATE", TARGET, "DENIED");
    }

    @Test
    void deactivateConcealsMissingStaff() {
        authorized();
        when(repository.currentStatus(TARGET)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.deactivate(TARGET)).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_DEACTIVATE", TARGET, "NOT_FOUND");
    }

    @Test
    void deactivateConflictsOnAlreadyRevoked() {
        authorized();
        when(repository.currentStatus(TARGET)).thenReturn(Optional.of("revoked"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.deactivate(TARGET)).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_DEACTIVATE", TARGET, "CONFLICT");
    }

    @Test
    void deactivateConcealsWhenTargetIdentityIsMissing() {
        authorized();
        when(repository.currentStatus(TARGET)).thenReturn(Optional.of("active"));
        when(repository.findUserIdBySubject(TARGET)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.deactivate(TARGET)).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_DEACTIVATE", TARGET, "NOT_FOUND");
    }

    @Test
    void deactivateAppendsRevokedEventAndAudits() {
        authorized();
        when(repository.currentStatus(TARGET)).thenReturn(Optional.of("active"));
        when(repository.findUserIdBySubject(TARGET)).thenReturn(Optional.of("target-id"));
        when(repository.findBySubject(TARGET)).thenReturn(Optional.of(member("deactivated")));
        assertEquals("deactivated", service.deactivate(TARGET).status());
        verify(repository).insertMembership(ORG, "target-id", "revoked");
        verify(repository).appendAudit(TenantContext.current(), "STAFF_DEACTIVATE", TARGET, "SUCCESS");
    }

    @Test
    void getDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(TARGET)).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_READ", TARGET, "DENIED");
    }

    @Test
    void getConcealsMissingStaff() {
        authorized();
        when(repository.findBySubject(TARGET)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(TARGET)).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_READ", TARGET, "NOT_FOUND");
    }

    @Test
    void getAuditsRead() {
        authorized();
        when(repository.findBySubject(TARGET)).thenReturn(Optional.of(member("active")));
        assertEquals(TARGET, service.get(TARGET).subject());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_READ", TARGET, "SUCCESS");
    }

    @Test
    void historyDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.history(TARGET)).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_HISTORY", TARGET, "DENIED");
    }

    @Test
    void historyConcealsMissingStaff() {
        authorized();
        when(repository.findBySubject(TARGET)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.history(TARGET)).status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_HISTORY", TARGET, "NOT_FOUND");
    }

    @Test
    void historyReturnsImmutableRowsAndAudits() {
        authorized();
        when(repository.findBySubject(TARGET)).thenReturn(Optional.of(member("active")));
        List<StaffHistory.MembershipEvent> events =
                List.of(new StaffHistory.MembershipEvent("m1", "active", "t0", null, "t0"));
        List<StaffHistory.RoleGrant> grants =
                List.of(new StaffHistory.RoleGrant("r1", "reception", "actor-id", "t0", null));
        when(repository.findMembershipEvents(TARGET)).thenReturn(events);
        when(repository.findRoleGrants(TARGET)).thenReturn(grants);
        StaffHistory result = service.history(TARGET);
        assertEquals(TARGET, result.subject());
        assertEquals(events, result.memberships());
        assertEquals(grants, result.roleGrants());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_HISTORY", TARGET, "SUCCESS");
    }

    @Test
    void valueRecordsExposeTheirParts() {
        StaffMember member = member("active");
        assertEquals(TARGET, member.subject());
        assertEquals("Target", member.displayName());
        assertEquals("active", member.status());
        assertEquals(List.of("reception"), member.roles());
        assertEquals(member, new StaffMember(TARGET, "Target", "active", List.of("reception")));
        assertEquals(member.hashCode(), new StaffMember(TARGET, "Target", "active", List.of("reception")).hashCode());
        assertEquals(member.toString(), new StaffMember(TARGET, "Target", "active", List.of("reception")).toString());

        StaffHistory.MembershipEvent event = new StaffHistory.MembershipEvent("m1", "active", "t0", null, "t0");
        assertEquals("m1", event.id());
        assertEquals("active", event.status());
        assertEquals("t0", event.validFrom());
        assertEquals(null, event.validTo());
        assertEquals("t0", event.createdAt());
        assertEquals(event, new StaffHistory.MembershipEvent("m1", "active", "t0", null, "t0"));
        assertEquals(event.hashCode(), new StaffHistory.MembershipEvent("m1", "active", "t0", null, "t0").hashCode());
        assertEquals(event.toString(), new StaffHistory.MembershipEvent("m1", "active", "t0", null, "t0").toString());

        StaffHistory.RoleGrant grant = new StaffHistory.RoleGrant("r1", "reception", "actor-id", "t0", null);
        assertEquals("r1", grant.id());
        assertEquals("reception", grant.roleKey());
        assertEquals("actor-id", grant.grantedBy());
        assertEquals("t0", grant.validFrom());
        assertEquals(null, grant.validTo());
        assertEquals(grant, new StaffHistory.RoleGrant("r1", "reception", "actor-id", "t0", null));
        assertEquals(grant.hashCode(), new StaffHistory.RoleGrant("r1", "reception", "actor-id", "t0", null).hashCode());
        assertEquals(grant.toString(), new StaffHistory.RoleGrant("r1", "reception", "actor-id", "t0", null).toString());

        StaffHistory history = new StaffHistory(TARGET, List.of(event), List.of(grant));
        assertEquals(TARGET, history.subject());
        assertEquals(List.of(event), history.memberships());
        assertEquals(List.of(grant), history.roleGrants());
        assertEquals(history, new StaffHistory(TARGET, List.of(event), List.of(grant)));
        assertEquals(history.hashCode(), new StaffHistory(TARGET, List.of(event), List.of(grant)).hashCode());
        assertEquals(history.toString(), new StaffHistory(TARGET, List.of(event), List.of(grant)).toString());

        StaffController.InviteRequest request = new StaffController.InviteRequest(TARGET, "Name", "reception");
        assertEquals(TARGET, request.subject());
        assertEquals("Name", request.displayName());
        assertEquals("reception", request.role());
        assertEquals(request, new StaffController.InviteRequest(TARGET, "Name", "reception"));
        assertEquals(request.hashCode(), new StaffController.InviteRequest(TARGET, "Name", "reception").hashCode());
        assertEquals(request.toString(), new StaffController.InviteRequest(TARGET, "Name", "reception").toString());
    }

    @Test
    void projectionsFreezeTheirHistoryListsAgainstCallerMutation() {
        java.util.List<String> mutableRoles = new java.util.ArrayList<>(List.of("reception"));
        StaffMember frozenMember = new StaffMember(TARGET, "Target", "active", mutableRoles);
        mutableRoles.add("tenant_admin");
        assertEquals(List.of("reception"), frozenMember.roles());
        assertThrows(UnsupportedOperationException.class, () -> frozenMember.roles().add("superuser"));

        java.util.List<StaffHistory.MembershipEvent> mutableEvents = new java.util.ArrayList<>(
                List.of(new StaffHistory.MembershipEvent("m1", "active", "t0", null, "t0")));
        java.util.List<StaffHistory.RoleGrant> mutableGrants = new java.util.ArrayList<>(
                List.of(new StaffHistory.RoleGrant("r1", "reception", "actor-id", "t0", null)));
        StaffHistory frozenHistory = new StaffHistory(TARGET, mutableEvents, mutableGrants);
        mutableEvents.clear();
        mutableGrants.clear();
        assertEquals(1, frozenHistory.memberships().size());
        assertEquals(1, frozenHistory.roleGrants().size());
        assertThrows(UnsupportedOperationException.class, () -> frozenHistory.roleGrants().clear());
    }
}
