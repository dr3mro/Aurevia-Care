package com.aurevia.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.aurevia.tenant.TenantContext;

/** S-G0-03b service branches: validation, authorization, cursor paging and audit-of-read. */
class AuditServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";

    private AuditRepository repository;
    private TransactionTemplate transactions;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditRepository.class);
        transactions = mock(TransactionTemplate.class);
        service = new AuditService(repository, new ActionPolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, AuditRepository.PURPOSE, "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
    }

    private AuditEvent event(long seq) {
        return new AuditEvent("id" + seq, "2026-11-19T00:00:00Z", "actor", "FACILITY_CREATE",
                "FACILITY", "F" + seq, "FACILITY_ADMIN", "FACILITY", "corr", "SUCCESS", seq);
    }

    @Test
    void blankCursorAndLimitUseDefaults() {
        authorized();
        when(repository.query(isNull(), eq(51), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of());
        assertEquals(0, service.query("  ", "  ", null, null, null, "  ", "  ").events().size());
        verify(repository).query(isNull(), eq(51), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void rejectsInvalidCursor() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.query("abc", null, null, null, null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.query("0", null, null, null, null, null, null)).status());
    }

    @Test
    void rejectsInvalidLimit() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.query(null, "0", null, null, null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.query(null, "201", null, null, null, null, null)).status());
    }

    @Test
    void rejectsInvalidFiltersAndInstants() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.query(null, null, "bad action", null, null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.query(null, null, null, "bad outcome", null, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.query(null, null, null, null, "bad type", null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.query(null, null, null, null, null, "nope", null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.query(null, null, null, null, null, null, "nope")).status());
    }

    @Test
    void deniesInactiveMembershipAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.query(null, null, null, null, null, null, null)).status());
        verify(repository).appendAudit(TenantContext.current(), "AUDIT_READ", "ALL", "DENIED");
        verify(repository, never()).query(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void deniesMissingPermissionAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.query(null, null, null, null, null, null, null)).status());
        verify(repository).appendAudit(TenantContext.current(), "AUDIT_READ", "ALL", "DENIED");
    }

    @Test
    void privacyAuditorIsAllowed() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("privacy_auditor"));
        when(repository.query(any(), anyInt(), any(), any(), any(), any(), any())).thenReturn(List.of());
        assertEquals(0, service.query(null, null, null, null, null, null, null).events().size());
    }

    @Test
    void returnsPageWithoutNextCursorWhenNotFull() {
        authorized();
        when(repository.query(isNull(), eq(51), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(event(3), event(2)));
        AuditPage page = service.query(null, "50", null, null, null, null, null);
        assertEquals(2, page.events().size());
        assertNull(page.nextCursor());
        verify(repository).appendAudit(TenantContext.current(), "AUDIT_READ", "ALL", "SUCCESS");
    }

    @Test
    void returnsNextCursorWhenMoreRowsExist() {
        authorized();
        when(repository.query(eq(9L), eq(3), eq("FACILITY_CREATE"), eq("SUCCESS"),
                eq("FACILITY"), isNull(), isNull()))
                .thenReturn(List.of(event(5), event(4), event(3)));
        AuditPage page = service.query("9", "2", "FACILITY_CREATE", "SUCCESS", "FACILITY", null, null);
        assertEquals(2, page.events().size());
        assertEquals("4", page.nextCursor());
        verify(repository).appendAudit(TenantContext.current(), "AUDIT_READ", "FACILITY_CREATE", "SUCCESS");
    }

    @Test
    void blankFiltersBecomeNullAndInstantsAreNormalized() {
        authorized();
        when(repository.query(isNull(), eq(51), isNull(), isNull(), isNull(), anyString(), anyString()))
                .thenReturn(List.of());
        service.query(null, "50", "  ", " ", " ", "2026-11-19T00:00:00Z", "2026-11-20T00:00:00Z");
        verify(repository).query(isNull(), eq(51), isNull(), isNull(), isNull(),
                eq("2026-11-19T00:00:00Z"), eq("2026-11-20T00:00:00Z"));
    }

    @Test
    void valueRecordsExposeTheirParts() {
        AuditEvent e = event(7);
        assertEquals("id7", e.id());
        assertEquals("2026-11-19T00:00:00Z", e.occurredAt());
        assertEquals("actor", e.actorId());
        assertEquals("FACILITY_CREATE", e.action());
        assertEquals("FACILITY", e.resourceType());
        assertEquals("F7", e.resourceId());
        assertEquals("FACILITY_ADMIN", e.purpose());
        assertEquals("FACILITY", e.scope());
        assertEquals("corr", e.correlationId());
        assertEquals("SUCCESS", e.outcome());
        assertEquals(7L, e.seq());
        assertEquals(e, new AuditEvent("id7", "2026-11-19T00:00:00Z", "actor", "FACILITY_CREATE",
                "FACILITY", "F7", "FACILITY_ADMIN", "FACILITY", "corr", "SUCCESS", 7));
        assertEquals(e.hashCode(), new AuditEvent("id7", "2026-11-19T00:00:00Z", "actor", "FACILITY_CREATE",
                "FACILITY", "F7", "FACILITY_ADMIN", "FACILITY", "corr", "SUCCESS", 7).hashCode());
        assertEquals(e.toString(), new AuditEvent("id7", "2026-11-19T00:00:00Z", "actor", "FACILITY_CREATE",
                "FACILITY", "F7", "FACILITY_ADMIN", "FACILITY", "corr", "SUCCESS", 7).toString());

        AuditPage page = new AuditPage(List.of(e), "6");
        assertEquals(1, page.events().size());
        assertEquals("6", page.nextCursor());
        assertThrows(UnsupportedOperationException.class, () -> page.events().clear());
        assertEquals(page, new AuditPage(List.of(e), "6"));
        assertEquals(page.hashCode(), new AuditPage(List.of(e), "6").hashCode());
        assertEquals(page.toString(), new AuditPage(List.of(e), "6").toString());
    }
}
