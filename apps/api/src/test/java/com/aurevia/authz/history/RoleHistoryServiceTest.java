package com.aurevia.authz.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.tenant.TenantContext;

/** IAM-02 read policy: tenant-admin-only, re-derived on every protected request. */
class RoleHistoryServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";

    private RoleHistoryStore store;
    private AccessRepository access;
    private RoleHistoryService service;

    @BeforeEach
    void setUp() {
        store = mock(RoleHistoryStore.class);
        access = mock(AccessRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new RoleHistoryService(store, access, new ActionPolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "STAFF_ADMIN", "corr-1"));
        when(access.hasActiveMembership(ACTOR)).thenReturn(true);
        when(access.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static RoleHistory.RoleChange grant() {
        return new RoleHistory.RoleChange("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "employee-a",
                "reception", "GRANT", ACTOR, "2026-11-21 10:00:00+00", "2026-11-21 09:00:00+00", null);
    }

    private ApiException failure(String subject) {
        return assertThrows(ApiException.class, () -> service.history(subject));
    }

    @Test
    void invalidSubjectIsRejectedBeforeAnyQuery() {
        for (String subject : new String[] {"BAD", null}) {
            ApiException exception = failure(subject);
            assertEquals(422, exception.status());
            assertEquals("INVALID_SUBJECT", exception.code());
        }
        verify(store, never()).findBySubject(any());
    }

    @Test
    void missingActiveMembershipIsDeniedAndAudited() {
        when(access.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException exception = failure("employee-a");
        assertEquals(403, exception.status());
        assertEquals("FORBIDDEN", exception.code());
        verify(access).appendAudit(any(), any(), any(), any(), any(), any(), any());
        verify(store, never()).findBySubject(any());
    }

    @Test
    void missingActionPermissionIsDeniedAndAudited() {
        when(access.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        ApiException exception = failure("employee-a");
        assertEquals(403, exception.status());
        assertEquals("FORBIDDEN", exception.code());
        verify(access).appendAudit(any(), any(), any(), any(), any(), any(), any());
        verify(store, never()).findBySubject(any());
    }

    @Test
    void unknownSubjectReturns404AndIsAudited() {
        when(store.findBySubject("employee-a")).thenReturn(List.of());
        ApiException exception = failure("employee-a");
        assertEquals(404, exception.status());
        assertEquals("NOT_FOUND", exception.code());
        verify(access).appendAudit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void tenantAdminReadsTheImmutableHistory() {
        when(store.findBySubject("employee-a")).thenReturn(List.of(grant()));
        RoleHistory history = service.history("employee-a");
        assertEquals("employee-a", history.subject());
        assertEquals(List.of(grant()), history.changes());
        verify(access).appendAudit(any(), any(), any(), any(), any(), any(), any());
    }
}
