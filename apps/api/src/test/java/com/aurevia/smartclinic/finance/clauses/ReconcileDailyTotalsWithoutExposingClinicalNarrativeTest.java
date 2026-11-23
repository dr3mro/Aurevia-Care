package com.aurevia.smartclinic.finance.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.finance.FinanceActions;
import com.aurevia.smartclinic.finance.FinanceContract.DailyTotal;
import com.aurevia.smartclinic.finance.FinanceContract.Reconciliation;
import com.aurevia.smartclinic.finance.FinanceRepository;
import com.aurevia.smartclinic.finance.FinanceService;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * FIN-01 clause 5 proof: finance/audit roles reconcile daily money totals, while
 * roles that must not see them are refused. The positive case asserts the exact
 * record components of the report so a clinical field could not be added without
 * failing this test; the negatives assert the forbidden status, the audited denial
 * and that the reconciliation query never runs. All fixtures are synthetic.
 */
class ReconcileDailyTotalsWithoutExposingClinicalNarrativeTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "finance-1111";
    private static final String CORRELATION = "corr-finance-reconcile";
    private static final String CLINICAL_ROLE = "clinician";

    /** Synthetic money-only rows: no patient, encounter, note or free-text field. */
    private static final List<DailyTotal> DAILY_TOTALS = List.of(
            new DailyTotal("2026-11-20", "USD", 1_300L, 900L, 100L, 50L, 750L, 3),
            new DailyTotal("2026-11-20", "EUR", 500L, 500L, 0L, 0L, 500L, 1));

    private FinanceRepository repository;
    private FinanceActions actions;
    private AuditAppender audit;
    private FinanceService service;
    private TenantContext tenant;

    @BeforeEach
    void setUp() {
        repository = mock(FinanceRepository.class);
        actions = new FinanceActions();
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new FinanceService(repository, actions, transactions, audit);
        tenant = new TenantContext(ORG, ACTOR, "FINANCE", CORRELATION);
        TenantContext.set(tenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant the subject active membership and exactly one active role. */
    private void activeRole(String role) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(role));
    }

    /** Assert the standard forbidden denial so a wrong status or code cannot pass. */
    private static ApiException forbidden(Runnable action) {
        ApiException denial = assertThrows(ApiException.class, action::run);
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        return denial;
    }

    /** Record component names, used to prove the report carries no clinical field. */
    private static Set<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    /** Assert the report read was audited as a success in the caller's tenant. */
    private void assertSuccessAudit() {
        verify(audit).append(eq(tenant), eq(FinanceActions.RECONCILE), eq(FinanceActions.RECONCILE),
                eq("FINANCE_INVOICE"), eq("FINANCE"), eq("REPORT"), eq("SUCCESS"));
    }

    /** Assert the denial was audited even though the report read was refused. */
    private void assertDeniedAudit() {
        verify(audit).append(eq(tenant), eq(FinanceActions.RECONCILE), eq(FinanceActions.RECONCILE),
                eq("FINANCE_INVOICE"), eq("FINANCE"), eq("REPORT"), eq("DENIED"));
    }

    @Test
    void reconcileReturnsFinanceOnlyDailyTotalsForFinanceRole() {
        activeRole("finance");
        when(repository.reconcile()).thenReturn(DAILY_TOTALS);

        Reconciliation report = service.reconcile();

        assertEquals(DAILY_TOTALS, report.days(), "only the synthetic finance totals reach the caller");
        assertEquals(Set.of("day", "currency", "issuedMinor", "paymentMinor", "refundMinor",
                "creditMinor", "netMinor", "entryCount"), componentNames(DailyTotal.class),
                "a report row carries money fields only, never a clinical narrative field");
        assertEquals(Set.of("days"), componentNames(Reconciliation.class));
        verify(repository).hasActiveMembership(ACTOR);
        verify(repository).activeRoles(ACTOR);
        verify(repository).reconcile();
        assertSuccessAudit();
        verify(audit, never()).append(eq(tenant), eq(FinanceActions.RECONCILE),
                eq(FinanceActions.RECONCILE), eq("FINANCE_INVOICE"), eq("FINANCE"),
                eq("REPORT"), eq("DENIED"));
    }

    @Test
    void reconcileAllowsTenantAdminAndPrivacyAuditor() {
        when(repository.reconcile()).thenReturn(DAILY_TOTALS);

        // Two separate invocations with one role each: a combined list would stop
        // noneMatch at the first allowed role and miss the privacy-auditor operand.
        activeRole("tenant_admin");
        clearInvocations(repository, audit);
        assertEquals(DAILY_TOTALS, service.reconcile().days());
        verify(repository).reconcile();
        assertSuccessAudit();

        activeRole("privacy_auditor");
        clearInvocations(repository, audit);
        assertEquals(DAILY_TOTALS, service.reconcile().days());
        verify(repository).reconcile();
        assertSuccessAudit();

        assertTrue(actions.allows(FinanceActions.RECONCILE, "privacy_auditor"));
    }

    @Test
    void reconcileIsDeniedForAClinicalRole() {
        activeRole(CLINICAL_ROLE);

        forbidden(() -> service.reconcile());

        verify(repository).activeRoles(ACTOR);
        verify(repository, never()).reconcile();
        assertDeniedAudit();
    }

    @Test
    void reconcileIsDeniedWithoutActiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);

        forbidden(() -> service.reconcile());

        verify(repository).hasActiveMembership(ACTOR);
        verify(repository, never()).activeRoles(anyString());
        verify(repository, never()).reconcile();
        assertDeniedAudit();
    }
}
