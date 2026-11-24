package com.aurevia.it.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.finance.FinanceActions;
import com.aurevia.finance.FinanceContract.Invoice;
import com.aurevia.finance.FinanceContract.InvoiceState;
import com.aurevia.finance.FinanceContract.VoidRequest;
import com.aurevia.finance.FinanceRepository;
import com.aurevia.finance.FinanceService;
import com.aurevia.tenant.TenantContext;

/**
 * Fast, database-free service branches for FIN-01 terminal-state and approval
 * rules. PostgreSQL command behaviour is covered by {@code FinanceFlowTest}.
 */
class FinanceServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "finance-1111";
    private static final String ID = "33333333-3333-3333-3333-333333333333";

    private FinanceRepository repository;
    private FinanceService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinanceRepository.class);
        AuditAppender audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new FinanceService(repository, new FinanceActions(), transactions, audit);
        TenantContext.set(new TenantContext(ORG, ACTOR, "FINANCE_INVOICE_VOID", "corr-1"));
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("finance"));
        when(repository.hasActiveMembership("admin-a")).thenReturn(true);
        when(repository.activeRoles("admin-a")).thenReturn(List.of("tenant_admin"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void voidingAnAlreadyVoidInvoiceIsRejected() {
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(invoice(InvoiceState.VOID, 2)));
        when(repository.findInvoice(ID)).thenReturn(Optional.of(invoice(InvoiceState.VOID, 2)));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.voidInvoice(ID, new VoidRequest("second reason", "admin-a"), 2));

        assertEquals(409, failure.status());
        assertEquals("INVALID_TRANSITION", failure.code());
        verify(repository, never()).voidInvoice(any(), anyInt(), any(), any());
    }

    private static Invoice invoice(InvoiceState state, int version) {
        return new Invoice(ID, version, ID, "USD", state, 1300L, 0L, 0L, 0L, 1300L, null,
                Instant.parse("2026-11-19T10:00:00Z"), null, "first reason", "admin-a");
    }
}
