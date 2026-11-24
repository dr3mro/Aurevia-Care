package com.aurevia.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Negative-path and tenant-isolation acceptance for the audit query API.
 *
 * <p>Only failures the current code can actually produce are asserted. This class
 * checks the service-level contracts: validation rejects over-long and unparsable
 * input, a role without audit permission is denied and that denial is recorded,
 * authorization and the audit append follow the bound
 * {@link com.aurevia.tenant.TenantContext}, and an empty result from the RLS-bounded
 * repository is returned as an empty page. Forced row-level security conceals other
 * tenants' rows at the database boundary and is proved end to end by
 * {@code AuditQueryTest}; the mocked repository here cannot prove concealment itself.</p>
 */
class AuditNegativePathsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String ACTOR = "admin-a";

    private AuditRepository repository;
    private TransactionTemplate transactions;
    private AuditService service;

    /** Build the service with a mocked RLS boundary and an inline transaction template. */
    @BeforeEach
    void setUp() {
        repository = mock(AuditRepository.class);
        transactions = mock(TransactionTemplate.class);
        service = new AuditService(repository, new ActionPolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG_A, ACTOR, AuditRepository.PURPOSE, "corr-negative"));
    }

    /** Remove the thread-bound tenant context so it cannot leak into another test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
    }

    private ApiException rejection(String cursor, String limit, String action, String outcome,
                                   String resourceType, String from, String to) {
        return assertThrows(ApiException.class,
                () -> service.query(cursor, limit, action, outcome, resourceType, from, to));
    }

    @Test
    void overLongFiltersAreRejected() {
        String tooLong = "A".repeat(65);
        ApiException action = rejection(null, null, tooLong, null, null, null, null);
        assertEquals(422, action.status());
        assertEquals("INVALID_ACTION", action.code());
        ApiException outcome = rejection(null, null, null, tooLong, null, null, null);
        assertEquals(422, outcome.status());
        assertEquals("INVALID_OUTCOME", outcome.code());
        ApiException resource = rejection(null, null, null, null, tooLong, null, null);
        assertEquals(422, resource.status());
        assertEquals("INVALID_RESOURCETYPE", resource.code());
    }

    @Test
    void malformedAndOverflowingNumbersAreRejected() {
        ApiException badLimit = rejection(null, "abc", null, null, null, null, null);
        assertEquals(422, badLimit.status());
        assertEquals("INVALID_LIMIT", badLimit.code());
        ApiException overflowLimit = rejection(null, "99999999999999999999", null, null, null, null, null);
        assertEquals(422, overflowLimit.status());
        assertEquals("INVALID_LIMIT", overflowLimit.code());
        ApiException overflowCursor = rejection("999999999999999999999999", null, null, null, null, null, null);
        assertEquals(422, overflowCursor.status());
        assertEquals("INVALID_CURSOR", overflowCursor.code());
    }

    @Test
    void clinicianRoleIsDeniedAndTheDenialIsAudited() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
        ApiException denial = rejection(null, null, null, null, null, null, null);
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(repository).appendAudit(TenantContext.current(), "AUDIT_READ", "ALL", "DENIED");
        verify(repository, never()).query(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void auditAppendFollowsTheBoundTenantContext() {
        authorized();
        when(repository.query(isNull(), eq(51), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of());

        service.query(null, null, null, null, null, null, null);

        TenantContext.set(new TenantContext(ORG_B, ACTOR, AuditRepository.PURPOSE, "corr-negative-b"));
        service.query(null, null, null, null, null, null, null);

        ArgumentCaptor<TenantContext> contexts = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository, times(2)).appendAudit(contexts.capture(), eq("AUDIT_READ"), eq("ALL"), eq("SUCCESS"));
        assertEquals(ORG_A, contexts.getAllValues().get(0).organizationId());
        assertEquals(ORG_B, contexts.getAllValues().get(1).organizationId());
    }

    @Test
    void resourceFilterWithNoRowsReturnsEmptyPage() {
        authorized();
        when(repository.query(isNull(), eq(51), isNull(), isNull(), eq("FACILITY"), isNull(), isNull()))
                .thenReturn(List.of());

        AuditPage page = service.query(null, null, null, null, "FACILITY", null, null);

        assertTrue(page.events().isEmpty());
        assertNull(page.nextCursor());
        verify(repository).query(isNull(), eq(51), isNull(), isNull(), eq("FACILITY"), isNull(), isNull());
        verify(repository).appendAudit(TenantContext.current(), "AUDIT_READ", "ALL", "SUCCESS");
    }
}
