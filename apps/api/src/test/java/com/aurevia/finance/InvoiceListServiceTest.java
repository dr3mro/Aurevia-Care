package com.aurevia.finance;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.finance.FinanceContract.Invoice;
import com.aurevia.finance.FinanceContract.InvoiceState;
import com.aurevia.finance.FinanceContract.View;
import com.aurevia.finance.FinanceRepository.InvoiceRow;
import com.aurevia.tenant.TenantContext;

/** ARC-03 cursor paging on the finance invoice list: validation, authorization and page boundaries. */
class InvoiceListServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ACTOR = "finance-1111";
    private static final String ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String CREATED_A = "2026-11-20T09:07:06Z";
    private static final String CREATED_B = "2026-11-20T09:07:05Z";
    private static final String CREATED_C = "2026-11-20T09:07:04Z";

    private FinanceRepository repository;
    private AuditAppender audit;
    private FinanceService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinanceRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new FinanceService(repository, new FinanceActions(), transactions, audit);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "FINANCE_INVOICE_READ", "corr-1"));
        authorized();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("finance"));
    }

    private static InvoiceRow row(String id, String createdAt, InvoiceState state) {
        Invoice invoice = new Invoice(id, 1, PATIENT, "USD", state, 1300L, 0L, 0L, 0L, 1300L,
                null, Instant.parse(createdAt), null, null, null);
        return new InvoiceRow(invoice, createdAt);
    }

    private ApiException failure(String cursor, String limit, String status, String patientId) {
        return assertThrows(ApiException.class, () -> service.list(cursor, limit, status, patientId));
    }

    @Test
    void absentAndBlankParametersUseDefaults() {
        InvoicePage page = service.list(null, null, null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        // Default limit is 50, so the repository fetches 51 rows.
        verify(repository).query(null, null, null, null, 51);

        service.list("  ", "  ", "  ", "  ");
        verify(repository, times(2)).query(null, null, null, null, 51);
    }

    @Test
    void invalidCursorsAreRejectedBeforeAnyQuery() {
        List<String> malformed = List.of(
                "plain",
                "a:b:c",
                ":" + ID_A,
                CREATED_A + ":",
                "not-a-time:" + ID_A,
                "BAD:" + ID_A,
                CREATED_A + ":not-a-uuid",
                "2026-11-20T09:07:06.1Z:" + ID_A);
        for (String cursor : malformed) {
            ApiException exception = failure(cursor, null, null, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_CURSOR", exception.code());
        }
        verify(repository, never()).query(any(), any(), any(), any(), anyInt());
    }

    @Test
    void invalidLimitsAreRejectedBeforeAnyQuery() {
        for (String limit : List.of("0", "201", "abc")) {
            ApiException exception = failure(null, limit, null, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_LIMIT", exception.code());
        }
        verify(repository, never()).query(any(), any(), any(), any(), anyInt());
    }

    @Test
    void invalidStatusesAreRejectedBeforeAnyQuery() {
        for (String status : List.of("all", "ISSUED", "unknown")) {
            ApiException exception = failure(null, null, status, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_STATUS", exception.code());
        }
        verify(repository, never()).query(any(), any(), any(), any(), anyInt());
    }

    @Test
    void invalidPatientIdsAreRejectedBeforeAnyQuery() {
        ApiException exception = failure(null, null, null, "not-a-uuid");
        assertEquals(422, exception.status());
        assertEquals("INVALID_PATIENTID", exception.code());
        verify(repository, never()).query(any(), any(), any(), any(), anyInt());
    }

    @Test
    void statusAndPatientIdFiltersArePassedThroughToTheRepository() {
        for (InvoiceState state : InvoiceState.values()) {
            service.list(null, null, state.wire(), null);
            verify(repository).query(null, null, state.wire(), null, 51);
        }
        service.list(null, null, null, PATIENT);
        verify(repository).query(null, null, null, PATIENT, 51);
    }

    @Test
    void emptyPageHasNoNextCursor() {
        when(repository.query(null, null, null, null, 51)).thenReturn(List.of());
        InvoicePage page = service.list(null, null, null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void exactlyLimitRowsHaveNoNextCursor() {
        when(repository.query(null, null, null, null, 3)).thenReturn(List.of(
                row(ID_A, CREATED_A, InvoiceState.DRAFT),
                row(ID_B, CREATED_B, InvoiceState.ISSUED)));
        InvoicePage page = service.list(null, "2", null, null);
        assertEquals(List.of(ID_A, ID_B),
                page.items().stream().map(item -> item.invoice().id()).toList());
        assertNull(page.nextCursor());
    }

    @Test
    void extraRowProducesCursorFromTheLastReturnedRow() {
        String cursor = CREATED_A + ":" + ID_A;
        when(repository.query(CREATED_A, ID_A, null, null, 3)).thenReturn(List.of(
                row(ID_A, CREATED_A, InvoiceState.DRAFT),
                row(ID_B, CREATED_B, InvoiceState.PARTIALLY_PAID),
                row(ID_C, CREATED_C, InvoiceState.PAID)));
        InvoicePage page = service.list(cursor, "2", null, null);
        assertEquals(List.of(ID_A, ID_B),
                page.items().stream().map(item -> item.invoice().id()).toList());
        assertEquals(CREATED_B + ":" + ID_B, page.nextCursor());
        verify(audit).append(TenantContext.current(), "FINANCE_INVOICE_READ", "FINANCE_INVOICE_READ",
                "FINANCE_INVOICE", "FINANCE", "ALL", "SUCCESS");
    }

    @Test
    void inactiveMembershipIsDeniedAndAudited() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException exception = failure(null, null, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), "FINANCE_INVOICE_READ", "FINANCE_INVOICE_READ",
                "FINANCE_INVOICE", "FINANCE", "ALL", "DENIED");
        verify(repository, never()).query(any(), any(), any(), any(), anyInt());
    }

    @Test
    void missingPermissionIsDeniedAndAudited() {
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        ApiException exception = failure(null, null, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), "FINANCE_INVOICE_READ", "FINANCE_INVOICE_READ",
                "FINANCE_INVOICE", "FINANCE", "ALL", "DENIED");
        verify(repository, never()).query(any(), any(), any(), any(), anyInt());
    }

    @Test
    void pageFreezesItsItemsAgainstCallerMutation() {
        List<View> mutable = new ArrayList<>(List.of(new View(
                row(ID_A, CREATED_A, InvoiceState.DRAFT).invoice(), List.of(), List.of())));
        InvoicePage page = new InvoicePage(mutable, CREATED_A + ":" + ID_A);
        mutable.clear();
        assertEquals(1, page.items().size());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }
}
