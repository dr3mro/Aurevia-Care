package com.aurevia.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.finance.FinanceContract.CreateInvoiceRequest;
import com.aurevia.finance.FinanceContract.EntryRequest;
import com.aurevia.finance.FinanceContract.EntryType;
import com.aurevia.finance.FinanceContract.Invoice;
import com.aurevia.finance.FinanceContract.InvoiceState;
import com.aurevia.finance.FinanceContract.LedgerEntry;
import com.aurevia.finance.FinanceContract.LineRequest;
import com.aurevia.finance.FinanceContract.VoidRequest;
import com.aurevia.finance.FinanceContract.View;
import com.aurevia.finance.FinanceRepository.Replay;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * Consolidated negative matrix for FIN-01 self-pay finance at the service and controller
 * boundaries (ARC-02, ARC-04). Every case runs the real {@code FinanceService} or the real
 * {@code FinanceController} over synthetic repository, transaction, audit and HTTP boundaries,
 * so the assertions cover validation ordering, authorization, non-disclosure, preconditions and
 * replay sequencing together rather than an isolated validator.
 *
 * <p>The empty-lookup case characterizes how the service maps an invisible identifier to 404
 * with a fixed message; a mocked repository has no tenant dimension, so it is a non-disclosure
 * proof and not an RLS isolation proof. Authoritative tenant isolation is owned by the finance
 * HTTP integration suites. The standalone MockMvc block likewise characterizes header parsing
 * and error mapping only; production route wiring is owned by those suites. This class changes
 * no production code, contract or migration, and all identifiers, subjects and amounts are
 * synthetic.</p>
 */
class FinanceNegativePathsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "finance-1111";
    private static final String ADMIN = "admin-1111";
    private static final String RECEPTION = "reception-1111";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final Instant CREATED = Instant.parse("2026-11-20T10:00:00Z");

    private FinanceRepository repository;
    private AuditAppender audit;
    private TransactionTemplate transactions;
    private FinanceService service;

    /** Wire the real service over synthetic transactional, repository and audit boundaries. */
    @BeforeEach
    void setUp() {
        repository = mock(FinanceRepository.class);
        audit = mock(AuditAppender.class);
        transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new FinanceService(repository, new FinanceActions(), transactions, audit);
        bind(ACTOR, "corr-neg");
        authorized(ACTOR, "finance");
        authorized(ADMIN, "tenant_admin");
        when(repository.patientExists(PATIENT)).thenReturn(true);
    }

    /** Release the thread-bound tenant context after each case. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Bind one synthetic organization and subject to the request thread. */
    private static void bind(String subject, String correlation) {
        TenantContext.set(new TenantContext(ORG, subject, "FINANCE", correlation));
    }

    /** Grant an active membership and the supplied role set to one synthetic subject. */
    private void authorized(String subject, String... roles) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(roles));
    }

    /** Build one synthetic itemized line; null fields model caller-supplied missing values. */
    private static LineRequest line(String description, Integer quantity, Long unitAmountMinor) {
        return new LineRequest(description, quantity, unitAmountMinor);
    }

    /** Build one synthetic issued-invoice projection for lookups and view reads. */
    private static Invoice invoice(InvoiceState state, int version) {
        return new Invoice(ID_A, version, PATIENT, "USD", state, 1300L, 0L, 0L, 0L, 1300L,
                null, CREATED, null, null, null);
    }

    /** Build one synthetic response view so view reads after a command can resolve. */
    private static View view() {
        return new View(invoice(InvoiceState.ISSUED, 2), List.of(), List.of());
    }

    /** Build one synthetic ledger entry; the replay path never reads its fields. */
    private static LedgerEntry entry(String reference) {
        return new LedgerEntry("entry-" + reference, ID_A, 2, EntryType.PAYMENT, 100L, "USD",
                reference, null, null, 1L, ACTOR, CREATED);
    }

    /** Assert one malformed command fails with the documented status and code. */
    private static ApiException invalid(String code, Runnable command) {
        ApiException failure = assertThrows(ApiException.class, command::run);
        assertEquals(422, failure.status());
        assertEquals(code, failure.code());
        return failure;
    }

    /** Assert an invisible identifier answers with a fixed not-found body and nothing else. */
    private static void concealed(Runnable command) {
        ApiException failure = assertThrows(ApiException.class, command::run);
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        assertEquals("invoice not found", failure.getMessage());
        assertNotEquals(403, failure.status());
        assertNotEquals("FORBIDDEN", failure.code());
    }

    /**
     * Missing, malformed, oversized and overflowing fields are refused before any transaction,
     * repository call or audit row, so an invalid command cannot reach a SQL cast or the ledger.
     * A missing reason or approver on a payment is valid today and is deliberately not asserted
     * as a failure here.
     */
    @Test
    void missingAndMalformedInputsFailBeforeAnyWrite() {
        invalid("INVALID_PATIENTID", () -> service.create(new CreateInvoiceRequest(
                "not-a-uuid", "USD", null, List.of(line("fee", 1, 100L)))));
        invalid("INVALID_CURRENCY", () -> service.create(new CreateInvoiceRequest(
                PATIENT, "US", null, List.of(line("fee", 1, 100L)))));
        invalid("INVALID_ITEMS", () -> service.create(new CreateInvoiceRequest(
                PATIENT, "USD", null, null)));
        invalid("INVALID_ITEMS", () -> service.create(new CreateInvoiceRequest(
                PATIENT, "USD", null, List.of())));
        invalid("INVALID_DESCRIPTION", () -> service.create(new CreateInvoiceRequest(
                PATIENT, "USD", null, List.of(line(" ", 1, 100L)))));
        invalid("INVALID_DESCRIPTION", () -> service.create(new CreateInvoiceRequest(
                PATIENT, "USD", null, List.of(line("x".repeat(121), 1, 100L)))));
        invalid("INVALID_QUANTITY", () -> service.create(new CreateInvoiceRequest(
                PATIENT, "USD", null, List.of(line("fee", null, 100L)))));
        invalid("INVALID_QUANTITY", () -> service.create(new CreateInvoiceRequest(
                PATIENT, "USD", null, List.of(line("fee", 0, 100L)))));
        invalid("INVALID_UNITAMOUNTMINOR", () -> service.create(new CreateInvoiceRequest(
                PATIENT, "USD", null, List.of(line("fee", 1, null)))));
        invalid("INVALID_UNITAMOUNTMINOR", () -> service.create(new CreateInvoiceRequest(
                PATIENT, "USD", null, List.of(line("fee", 1, -1L)))));
        ApiException multiplyOverflow = invalid("AMOUNT_OVERFLOW", () -> service.create(
                new CreateInvoiceRequest(PATIENT, "USD", null,
                        List.of(line("multiply", 2, Long.MAX_VALUE)))));
        assertEquals("amountMinor overflows the money range", multiplyOverflow.getMessage());
        long half = Long.MAX_VALUE / 2 + 1;
        ApiException sumOverflow = invalid("AMOUNT_OVERFLOW", () -> service.create(
                new CreateInvoiceRequest(PATIENT, "USD", null,
                        List.of(line("sum a", 1, half), line("sum b", 1, half)))));
        assertEquals("invoice total overflows the money range", sumOverflow.getMessage());

        invalid("INVALID_ID", () -> service.issue("not-a-uuid", 1));
        invalid("INVALID_ID", () -> service.get("not-a-uuid"));
        invalid("INVALID_AMOUNTMINOR", () -> service.postEntry(ID_A, EntryType.PAYMENT,
                new EntryRequest(0L, "NEG-AMOUNT", null, null), 1));
        invalid("INVALID_REFERENCE", () -> service.postEntry(ID_A, EntryType.PAYMENT,
                new EntryRequest(100L, " ", null, null), 1));
        invalid("INVALID_REFERENCE", () -> service.postEntry(ID_A, EntryType.PAYMENT,
                new EntryRequest(100L, "x".repeat(129), null, null), 1));
        invalid("INVALID_REASON", () -> service.postEntry(ID_A, EntryType.CREDIT,
                new EntryRequest(100L, "NEG-REASON", " ", ADMIN), 1));
        invalid("INVALID_REASON", () -> service.postEntry(ID_A, EntryType.CREDIT,
                new EntryRequest(100L, "NEG-REASON", "x".repeat(501), ADMIN), 1));
        invalid("INVALID_APPROVERSUBJECT", () -> service.postEntry(ID_A, EntryType.CREDIT,
                new EntryRequest(100L, "NEG-APPROVER", "write-off", "Bad_Case"), 1));
        invalid("INVALID_REASON", () -> service.voidInvoice(ID_A,
                new VoidRequest("x".repeat(501), ADMIN), 1));
        invalid("INVALID_APPROVERSUBJECT", () -> service.voidInvoice(ID_A,
                new VoidRequest("cancelled", "Bad_Case"), 1));

        verify(repository, never()).patientExists(anyString());
        verify(repository, never()).loadForUpdate(anyString());
        verify(repository, never()).findInvoice(anyString());
        verify(repository, never()).insertInvoice(any(), any(), any(), anyLong());
        verify(repository, never()).insertItem(anyString(), anyInt(), anyString(), anyInt(),
                anyLong(), anyLong());
        verify(repository, never()).issueInvoice(anyString(), anyInt(), anyLong());
        verify(repository, never()).voidInvoice(anyString(), anyInt(), anyString(), anyString());
        verify(repository, never()).insertEntry(anyString(), anyInt(), any(), anyLong(), anyString(),
                anyString(), any(), any(), anyLong(), anyString());
        verify(repository, never()).updateInvoice(anyString(), anyInt(), anyLong(), anyLong(),
                anyLong(), anyLong(), any());
        verify(repository, never()).nextReceiptNo();
        verify(transactions, never()).execute(any());
        verifyNoInteractions(audit);
    }

    /**
     * An active caller without a finance role is denied on each mutating command exercised here. The denial is
     * audited in the caller's tenant with the action as both purpose and action, using "NEW" as
     * the create resource id, and no finance row is read or written.
     */
    @Test
    void wrongRoleDeniedAndAudited() {
        bind(RECEPTION, "corr-role");
        authorized(RECEPTION, "reception");
        TenantContext tenant = TenantContext.current();

        CreateInvoiceRequest validCreate = new CreateInvoiceRequest(PATIENT, "USD", null,
                List.of(line("synthetic fee", 1, 100L)));
        ApiException create = assertThrows(ApiException.class, () -> service.create(validCreate));
        assertEquals(403, create.status());
        assertEquals("FORBIDDEN", create.code());
        assertEquals("no active membership with " + FinanceActions.CREATE + " permission",
                create.getMessage());
        verify(audit).append(tenant, FinanceActions.CREATE, FinanceActions.CREATE,
                "FINANCE_INVOICE", "FINANCE", "NEW", "DENIED");

        ApiException issue = assertThrows(ApiException.class, () -> service.issue(ID_A, 1));
        assertEquals(403, issue.status());
        assertEquals("FORBIDDEN", issue.code());
        assertEquals("no active membership with " + FinanceActions.ISSUE + " permission",
                issue.getMessage());
        verify(audit).append(tenant, FinanceActions.ISSUE, FinanceActions.ISSUE,
                "FINANCE_INVOICE", "FINANCE", ID_A, "DENIED");

        ApiException payment = assertThrows(ApiException.class, () -> service.postEntry(ID_A,
                EntryType.PAYMENT, new EntryRequest(100L, "ROLE-PAY", null, null), 1));
        assertEquals(403, payment.status());
        assertEquals("FORBIDDEN", payment.code());
        assertEquals("no active membership with " + FinanceActions.PAYMENT + " permission",
                payment.getMessage());
        verify(audit).append(tenant, FinanceActions.PAYMENT, FinanceActions.PAYMENT,
                "FINANCE_INVOICE", "FINANCE", ID_A, "DENIED");

        verify(repository, times(3)).hasActiveMembership(RECEPTION);
        verify(repository, times(3)).activeRoles(RECEPTION);
        verify(repository, never()).patientExists(anyString());
        verify(repository, never()).loadForUpdate(anyString());
        verify(repository, never()).insertInvoice(any(), any(), any(), anyLong());
        verify(repository, never()).insertEntry(anyString(), anyInt(), any(), anyLong(), anyString(),
                anyString(), any(), any(), anyLong(), anyString());
        verify(repository, never()).updateInvoice(anyString(), anyInt(), anyLong(), anyLong(),
                anyLong(), anyLong(), any());
    }

    /**
     * An identifier invisible to the active tenant is indistinguishable from a missing invoice:
     * every command answers 404 with the constant message, never 403, and no record field is
     * echoed. The service writes no audit row on this path because not-found is thrown before
     * the success audit is reached.
     */
    @Test
    void otherTenantsIdLooksLikeNotFound() {
        when(repository.loadForUpdate(ID_B)).thenReturn(Optional.empty());
        when(repository.findInvoice(ID_B)).thenReturn(Optional.empty());

        concealed(() -> service.get(ID_B));
        concealed(() -> service.issue(ID_B, 1));
        concealed(() -> service.postEntry(ID_B, EntryType.PAYMENT,
                new EntryRequest(100L, "PEER-PAY", null, null), 1));
        concealed(() -> service.voidInvoice(ID_B,
                new VoidRequest("synthetic cancellation", ADMIN), 1));

        verify(repository, never()).items(anyString());
        verify(repository, never()).entries(anyString());
        verify(repository, never()).findByReference(anyString());
        verify(repository, never()).issueInvoice(anyString(), anyInt(), anyLong());
        verify(repository, never()).voidInvoice(anyString(), anyInt(), anyString(), anyString());
        verify(repository, never()).insertEntry(anyString(), anyInt(), any(), anyLong(), anyString(),
                anyString(), any(), any(), anyLong(), anyString());
        verify(repository, never()).updateInvoice(anyString(), anyInt(), anyLong(), anyLong(),
                anyLong(), anyLong(), any());
        verify(repository, never()).nextReceiptNo();
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A stored version newer than the caller's is refused with 412 on every mutating command and
     * changes nothing. The replay lookup for a payment runs before the version check, as the
     * verification shows. At the HTTP boundary a missing precondition is 428 and a malformed or
     * non-positive one is 422, both before the service is called; a weak validator passes the
     * parsed version through.
     */
    @Test
    void staleVersionRejected() throws Exception {
        when(repository.loadForUpdate(ID_A)).thenReturn(Optional.of(invoice(InvoiceState.DRAFT, 2)));
        when(repository.findInvoice(ID_A)).thenReturn(Optional.of(invoice(InvoiceState.DRAFT, 2)));

        ApiException issue = assertThrows(ApiException.class, () -> service.issue(ID_A, 1));
        assertEquals(412, issue.status());
        assertEquals("STALE_VERSION", issue.code());
        assertEquals("the invoice changed since it was read", issue.getMessage());

        ApiException voided = assertThrows(ApiException.class, () -> service.voidInvoice(ID_A,
                new VoidRequest("synthetic cancellation", ADMIN), 1));
        assertEquals(412, voided.status());
        assertEquals("STALE_VERSION", voided.code());

        ApiException payment = assertThrows(ApiException.class, () -> service.postEntry(ID_A,
                EntryType.PAYMENT, new EntryRequest(100L, "STALE-NEG", null, null), 1));
        assertEquals(412, payment.status());
        assertEquals("STALE_VERSION", payment.code());
        verify(repository).findByReference("STALE-NEG");

        verify(repository, never()).issueInvoice(anyString(), anyInt(), anyLong());
        verify(repository, never()).voidInvoice(anyString(), anyInt(), anyString(), anyString());
        verify(repository, never()).insertEntry(anyString(), anyInt(), any(), anyLong(), anyString(),
                anyString(), any(), any(), anyLong(), anyString());
        verify(repository, never()).updateInvoice(anyString(), anyInt(), anyLong(), anyLong(),
                anyLong(), anyLong(), any());
        verify(repository, never()).nextReceiptNo();
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), eq("SUCCESS"));

        FinanceService httpService = mock(FinanceService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FinanceController(httpService))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();

        mvc.perform(post("/api/v1/finance/invoices/" + ID_A + "/issue")
                        .header("X-Synthetic-Subject", ACTOR)
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(post("/api/v1/finance/invoices/" + ID_A + "/issue")
                        .header("X-Synthetic-Subject", ACTOR)
                        .header("X-Active-Organization", ORG)
                        .header("If-Match", "abc")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
        mvc.perform(post("/api/v1/finance/invoices/" + ID_A + "/issue")
                        .header("X-Synthetic-Subject", ACTOR)
                        .header("X-Active-Organization", ORG)
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
        verifyNoInteractions(httpService);

        when(httpService.issue(ID_A, 2)).thenReturn(view());
        mvc.perform(post("/api/v1/finance/invoices/" + ID_A + "/issue")
                        .header("X-Synthetic-Subject", ACTOR)
                        .header("X-Active-Organization", ORG)
                        .header("If-Match", "W/\"2\"")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.id").value(ID_A));
        verify(httpService).issue(ID_A, 2);
    }

    /**
     * A repeated reference with the same payload replays the original outcome without a second
     * entry, receipt, version or balance change; the replay check precedes the version check, so
     * even a stale caller version replays. The same reference with a different amount conflicts
     * with 409 and a CONFLICT audit. The replay fingerprint is built from the stored invoice
     * currency exactly as the service builds it.
     */
    @Test
    void duplicateReferenceReplaysAndChangedPayloadConflicts() {
        String reference = "REPLAY-NEG";
        Invoice issued = invoice(InvoiceState.ISSUED, 2);
        when(repository.loadForUpdate(ID_A)).thenReturn(Optional.of(issued));
        when(repository.findInvoice(ID_A)).thenReturn(Optional.of(issued));
        String digest = FinanceInputs.digest(ID_A, EntryType.PAYMENT, 100L, issued.currency(),
                null, null);
        when(repository.findByReference(reference)).thenReturn(
                Optional.of(new Replay(digest, entry(reference))));

        View replay = service.postEntry(ID_A, EntryType.PAYMENT,
                new EntryRequest(100L, reference, null, null), 1);
        assertEquals(ID_A, replay.invoice().id());
        assertEquals(2, replay.invoice().version());
        assertEquals(1300L, replay.invoice().balanceMinor());
        verify(audit).append(TenantContext.current(), "FINANCE_PAYMENT_RECORD_REPLAY",
                "FINANCE_PAYMENT_RECORD_REPLAY", "FINANCE_INVOICE", "FINANCE", ID_A, "SUCCESS");

        ApiException conflict = assertThrows(ApiException.class, () -> service.postEntry(ID_A,
                EntryType.PAYMENT, new EntryRequest(200L, reference, null, null), 2));
        assertEquals(409, conflict.status());
        assertEquals("REFERENCE_CONFLICT", conflict.code());
        assertEquals("the reference was used with a different payload", conflict.getMessage());
        verify(audit).append(TenantContext.current(), FinanceActions.PAYMENT, FinanceActions.PAYMENT,
                "FINANCE_INVOICE", "FINANCE", ID_A, "CONFLICT");

        verify(repository, never()).insertEntry(anyString(), anyInt(), any(), anyLong(), anyString(),
                anyString(), any(), any(), anyLong(), anyString());
        verify(repository, never()).updateInvoice(anyString(), anyInt(), anyLong(), anyLong(),
                anyLong(), anyLong(), any());
        verify(repository, never()).nextReceiptNo();
    }
}
