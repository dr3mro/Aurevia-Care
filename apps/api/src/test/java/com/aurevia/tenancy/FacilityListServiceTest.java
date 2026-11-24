package com.aurevia.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

/** ARC-03 cursor paging on the facility list: validation, authorization and page boundaries. */
class FacilityListServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";
    private static final String ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";

    private FacilityRepository repository;
    private TransactionTemplate transactions;
    private FacilityService service;

    @BeforeEach
    void setUp() {
        repository = mock(FacilityRepository.class);
        transactions = mock(TransactionTemplate.class);
        service = new FacilityService(repository, new ActionPolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, TenantContext.PURPOSE, "corr-1"));
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

    private static FacilityRepository.FacilityRow row(String code, String id) {
        return new FacilityRepository.FacilityRow(new Facility(ORG, code, "Name " + code, "UTC"), id);
    }

    private ApiException failure(String cursor, String limit) {
        return assertThrows(ApiException.class, () -> service.list(cursor, limit));
    }

    @Test
    void absentAndBlankParametersUseDefaults() {
        FacilityPage page = service.list(null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        // Default limit is 50, so the repository fetches 51 rows.
        verify(repository).query(null, null, 51);

        service.list("  ", "  ");
        verify(repository, times(2)).query(null, null, 51);
    }

    @Test
    void invalidCursorsAreRejectedBeforeAnyQuery() {
        for (String cursor : List.of("plain", "alpha:beta:gamma", ":id", "ALPHA:",
                "lower:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "ALPHA:not-a-uuid",
                "رمز:" + ID_A, "A".repeat(33) + ":" + ID_A)) {
            ApiException exception = failure(cursor, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_CURSOR", exception.code());
        }
        verify(repository, never()).query(any(), any(), anyInt());
    }

    @Test
    void acceptsMaxLengthCodeCursor() {
        String code = "A".repeat(32);
        when(repository.query(code, ID_A, 51)).thenReturn(List.of());
        FacilityPage page = service.list(code + ":" + ID_A, null);
        assertTrue(page.items().isEmpty());
        verify(repository).query(code, ID_A, 51);
    }

    @Test
    void invalidLimitsAreRejectedBeforeAnyQuery() {
        for (String limit : List.of("0", "201", "abc")) {
            ApiException exception = failure(null, limit);
            assertEquals(422, exception.status());
            assertEquals("INVALID_LIMIT", exception.code());
        }
        verify(repository, never()).query(any(), any(), anyInt());
    }

    @Test
    void emptyPageHasNoNextCursor() {
        when(repository.query(null, null, 51)).thenReturn(List.of());
        FacilityPage page = service.list(null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void exactlyLimitRowsHaveNoNextCursor() {
        when(repository.query(null, null, 3)).thenReturn(List.of(row("ALPHA", ID_A), row("BRAVO", ID_B)));
        FacilityPage page = service.list(null, "2");
        assertEquals(List.of("ALPHA", "BRAVO"), page.items().stream().map(Facility::code).toList());
        assertNull(page.nextCursor());
    }

    @Test
    void extraRowProducesCursorFromTheLastReturnedRow() {
        String cursor = "ALPHA:" + ID_A;
        when(repository.query("ALPHA", ID_A, 3)).thenReturn(
                List.of(row("ALPHA", ID_A), row("BRAVO", ID_B), row("CHARLIE", ID_C)));
        FacilityPage page = service.list(cursor, "2");
        assertEquals(List.of("ALPHA", "BRAVO"), page.items().stream().map(Facility::code).toList());
        assertEquals("BRAVO:" + ID_B, page.nextCursor());
        verify(repository).appendAudit(TenantContext.current(), "FACILITY_READ", "FACILITY", "ALL", "SUCCESS");
    }

    @Test
    void inactiveMembershipIsDenied() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, failure(null, null).status());
        verify(repository, never()).query(any(), any(), anyInt());
    }

    @Test
    void missingPermissionIsDenied() {
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, failure(null, null).status());
        verify(repository, never()).query(any(), any(), anyInt());
    }

    @Test
    void pageAndRowFreezeTheirPartsAndExposeValueSemantics() {
        List<Facility> mutable = new ArrayList<>(List.of(new Facility(ORG, "ALPHA", "Alpha", "UTC")));
        FacilityPage page = new FacilityPage(mutable, "ALPHA:" + ID_A);
        mutable.clear();
        assertEquals(1, page.items().size());
        assertEquals("ALPHA:" + ID_A, page.nextCursor());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());

        FacilityPage equal = new FacilityPage(List.of(new Facility(ORG, "ALPHA", "Alpha", "UTC")), "ALPHA:" + ID_A);
        assertEquals(page, equal);
        assertEquals(page.hashCode(), equal.hashCode());
        assertEquals(page.toString(), equal.toString());

        FacilityRepository.FacilityRow first = row("ALPHA", ID_A);
        assertEquals("ALPHA", first.facility().code());
        assertEquals(ID_A, first.id());
        assertEquals(first, row("ALPHA", ID_A));
        assertEquals(first.hashCode(), row("ALPHA", ID_A).hashCode());
        assertEquals(first.toString(), row("ALPHA", ID_A).toString());
    }
}
