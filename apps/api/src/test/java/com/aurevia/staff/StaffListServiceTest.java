package com.aurevia.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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

/** ARC-03 cursor paging on the staff list: validation, authorization and page boundaries. */
class StaffListServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";
    private static final String ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";

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
        authorized();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
    }

    private static StaffRepository.StaffRow row(String subject, String id) {
        return new StaffRepository.StaffRow(
                new StaffMember(subject, "Name " + subject, "active", List.of("reception")), id);
    }

    private ApiException failure(String cursor, String limit, String status) {
        return assertThrows(ApiException.class, () -> service.list(cursor, limit, status));
    }

    @Test
    void absentAndBlankParametersUseDefaults() {
        StaffPage page = service.list(null, null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        // default limit is 50, so the repository fetches 51 rows.
        verify(repository).query(null, null, null, 51);

        service.list("  ", "  ", "  ");
        verify(repository, org.mockito.Mockito.times(2)).query(null, null, null, 51);
    }

    @Test
    void invalidCursorsAreRejectedBeforeAnyQuery() {
        for (String cursor : List.of("plain", "a:b:c", ":id", "subject:",
                "BAD:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "subject:not-a-uuid")) {
            ApiException exception = failure(cursor, null, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_CURSOR", exception.code());
        }
        verify(repository, never()).query(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void invalidLimitsAreRejectedBeforeAnyQuery() {
        for (String limit : List.of("0", "201", "abc")) {
            ApiException exception = failure(null, limit, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_LIMIT", exception.code());
        }
        verify(repository, never()).query(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void invalidStatusesAreRejectedBeforeAnyQuery() {
        for (String status : List.of("all", "ACTIVE", "revoked")) {
            ApiException exception = failure(null, null, status);
            assertEquals(422, exception.status());
            assertEquals("INVALID_STATUS", exception.code());
        }
        verify(repository, never()).query(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void statusFiltersMapToDatabaseMembershipStatus() {
        service.list(null, null, "active");
        verify(repository).query(null, null, "active", 51);
        service.list(null, null, "deactivated");
        verify(repository).query(null, null, "revoked", 51);
    }

    @Test
    void emptyPageHasNoNextCursor() {
        when(repository.query(null, null, null, 51)).thenReturn(List.of());
        StaffPage page = service.list(null, null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void exactlyLimitRowsHaveNoNextCursor() {
        when(repository.query(null, null, null, 3)).thenReturn(List.of(row("alpha-a", ID_A), row("beta-a", ID_B)));
        StaffPage page = service.list(null, "2", null);
        assertEquals(List.of("alpha-a", "beta-a"), page.items().stream().map(StaffMember::subject).toList());
        assertNull(page.nextCursor());
    }

    @Test
    void extraRowProducesCursorFromTheLastReturnedRow() {
        String cursor = "alpha-a:" + ID_A;
        when(repository.query("alpha-a", ID_A, null, 3)).thenReturn(
                List.of(row("alpha-a", ID_A), row("beta-a", ID_B), row("gamma-a", ID_C)));
        StaffPage page = service.list(cursor, "2", null);
        assertEquals(List.of("alpha-a", "beta-a"), page.items().stream().map(StaffMember::subject).toList());
        assertEquals("beta-a:" + ID_B, page.nextCursor());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_READ", "ALL", "SUCCESS");
    }

    @Test
    void inactiveMembershipIsDeniedAndAudited() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException exception = failure(null, null, null);
        assertEquals(403, exception.status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_READ", "ALL", "DENIED");
        verify(repository, never()).query(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void missingPermissionIsDeniedAndAudited() {
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        ApiException exception = failure(null, null, null);
        assertEquals(403, exception.status());
        verify(repository).appendAudit(TenantContext.current(), "STAFF_READ", "ALL", "DENIED");
        verify(repository, never()).query(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void pageAndRowFreezeTheirPartsAndExposeValueSemantics() {
        List<StaffMember> mutable = new ArrayList<>(List.of(new StaffMember("alpha-a", "Alpha", "active", List.of())));
        StaffPage page = new StaffPage(mutable, "alpha-a:" + ID_A);
        mutable.clear();
        assertEquals(1, page.items().size());
        assertEquals("alpha-a:" + ID_A, page.nextCursor());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());

        assertEquals(page, new StaffPage(List.of(new StaffMember("alpha-a", "Alpha", "active", List.of())),
                "alpha-a:" + ID_A));
        assertEquals(page.hashCode(), new StaffPage(List.of(new StaffMember("alpha-a", "Alpha", "active", List.of())),
                "alpha-a:" + ID_A).hashCode());
        assertEquals(page.toString(), new StaffPage(List.of(new StaffMember("alpha-a", "Alpha", "active", List.of())),
                "alpha-a:" + ID_A).toString());

        StaffRepository.StaffRow first = row("alpha-a", ID_A);
        assertEquals("alpha-a", first.member().subject());
        assertEquals(ID_A, first.id());
        assertEquals(first, row("alpha-a", ID_A));
        assertEquals(first.hashCode(), row("alpha-a", ID_A).hashCode());
        assertEquals(first.toString(), row("alpha-a", ID_A).toString());
    }
}
