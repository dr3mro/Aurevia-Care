package com.aurevia.smartclinic.finance.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.finance.FinanceActions;
import com.aurevia.smartclinic.finance.FinanceContract.CreateInvoiceRequest;
import com.aurevia.smartclinic.finance.FinanceContract.EntryRequest;
import com.aurevia.smartclinic.finance.FinanceContract.EntryType;
import com.aurevia.smartclinic.finance.FinanceContract.Invoice;
import com.aurevia.smartclinic.finance.FinanceContract.InvoiceItem;
import com.aurevia.smartclinic.finance.FinanceContract.InvoiceState;
import com.aurevia.smartclinic.finance.FinanceContract.LedgerEntry;
import com.aurevia.smartclinic.finance.FinanceContract.LineRequest;
import com.aurevia.smartclinic.finance.FinanceContract.View;
import com.aurevia.smartclinic.finance.FinanceInputs;
import com.aurevia.smartclinic.finance.FinanceRepository;
import com.aurevia.smartclinic.finance.FinanceRepository.Replay;
import com.aurevia.smartclinic.finance.FinanceService;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Clause-level proof for FIN-01: balance changes are applied atomically under a
 * row lock and version precondition, a reused reference replays or conflicts
 * instead of posting twice, and every posted entry takes the next tenant-local
 * receipt number. A fresh test-only repository drives the real
 * {@link com.aurevia.smartclinic.finance.FinanceService} without PostgreSQL, so
 * each assertion is deterministic; the durable rollback and concurrency proof
 * lives in the real-database finance integration suite. All identifiers and
 * amounts are synthetic.
 */
class ApplyBalanceChangesTransactionallyDeduplicatePaymentReferencesTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ACTOR = "finance-1111";
    private static final String ADMIN = "admin-1111";
    private static final String OUTSIDER = "outsider-9999";
    private static final Instant CREATED = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant ISSUED_AT = Instant.parse("2026-11-20T10:01:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-11-20T10:02:00Z");

    private LedgerFakeRepository repository;
    private FinanceService service;

    @BeforeEach
    void setUp() {
        repository = new LedgerFakeRepository();
        AuditAppender audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        // Run the callback inline so the service's single transaction boundary is
        // exercised without a database; the fake holds the whole committed state.
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new FinanceService(repository, new FinanceActions(), transactions, audit);
        TenantContext.set(new TenantContext(ORG, ACTOR, "FINANCE", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Create a one-line draft invoice for the synthetic patient. */
    private View createInvoice(String currency, LineRequest... lines) {
        return service.create(new CreateInvoiceRequest(PATIENT, currency, "ENCOUNTER-1", List.of(lines)));
    }

    /** Create and issue a synthetic invoice with the given total in minor units. */
    private String newIssued(long total) {
        String id = createInvoice("USD", new LineRequest("fee", 1, total)).invoice().id();
        service.issue(id, 1);
        return id;
    }

    /** Build a synthetic ledger command. */
    private static EntryRequest entry(long amount, String reference, String reason, String approver) {
        return new EntryRequest(amount, reference, reason, approver);
    }

    /**
     * One posted payment reduces the balance, moves the state, advances the
     * version and allocates the first receipt in the same callback; a second
     * payment takes the next consecutive receipt number.
     */
    @Test
    void aPaymentUpdatesBalanceAndAllocatesTheNextReceiptAtomically() {
        String id = newIssued(1300);
        View first = service.postEntry(id, EntryType.PAYMENT, entry(300, "ATOMIC-1", "desk", null), 2);
        assertEquals(1000L, first.invoice().balanceMinor());
        assertEquals(300L, first.invoice().paidMinor());
        assertEquals(InvoiceState.PARTIALLY_PAID, first.invoice().state());
        assertEquals(3, first.invoice().version(), "a posted entry advances the invoice version atomically");
        assertEquals(1, first.entries().size());
        LedgerEntry firstEntry = first.entries().get(0);
        assertTrue(firstEntry.receiptNo() > 0, "a posted entry carries a positive receipt number");
        assertEquals(2, firstEntry.invoiceVersion(), "the entry snapshots the version it was posted against");
        assertEquals("USD", firstEntry.currency());

        View second = service.postEntry(id, EntryType.PAYMENT, entry(400, "ATOMIC-2", null, null), 3);
        assertEquals(600L, second.invoice().balanceMinor());
        assertEquals(700L, second.invoice().paidMinor());
        assertEquals(2, second.entries().size());
        assertEquals(firstEntry.receiptNo() + 1, second.entries().get(1).receiptNo(),
                "receipt numbers are monotonic across postings");
    }

    /**
     * Repeating a reference with an identical payload returns the original
     * committed outcome: no second balance change, ledger row, version bump or
     * receipt number.
     */
    @Test
    void anIdenticalReferenceReplaysWithoutASecondBalanceChangeOrReceipt() {
        String id = newIssued(1300);
        View first = service.postEntry(id, EntryType.PAYMENT, entry(300, "REPLAY-SAME", "desk", null), 2);
        long receiptNo = first.entries().get(0).receiptNo();

        View replay = service.postEntry(id, EntryType.PAYMENT, entry(300, "REPLAY-SAME", "desk", null), 3);
        assertEquals(1000L, replay.invoice().balanceMinor());
        assertEquals(first.invoice().version(), replay.invoice().version(),
                "a replay changes no version");
        assertEquals(1, replay.entries().size(), "a replay posts no second ledger entry");
        assertEquals(receiptNo, replay.entries().get(0).receiptNo(),
                "a replay allocates no second receipt");
    }

    /** A reference reused with a different payload is refused with 409 and changes no money. */
    @Test
    void aReusedReferenceWithADifferentPayloadIsRefused() {
        String id = newIssued(1300);
        service.postEntry(id, EntryType.PAYMENT, entry(300, "SHARED-REF", "desk", null), 2);

        ApiException clash = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.PAYMENT, entry(400, "SHARED-REF", "desk", null), 3));
        assertEquals(409, clash.status());
        assertEquals("REFERENCE_CONFLICT", clash.code());
        View after = service.get(id);
        assertEquals(1000L, after.invoice().balanceMinor());
        assertEquals(1, after.entries().size());
    }

    /**
     * An over-balance payment is refused before any receipt is allocated; the
     * next valid posting therefore receives the first receipt number.
     */
    @Test
    void anOverBalancePaymentIsRefusedWithoutChangingBalanceOrAllocatingAReceipt() {
        String id = newIssued(1300);
        ApiException failure = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.PAYMENT, entry(1301, "OVER-REF", null, null), 2));
        assertEquals(422, failure.status());
        assertEquals("INSUFFICIENT_BALANCE", failure.code());
        View after = service.get(id);
        assertEquals(1300L, after.invoice().balanceMinor());
        assertEquals(0L, after.invoice().paidMinor());
        assertEquals(0, after.entries().size());

        View next = service.postEntry(id, EntryType.PAYMENT, entry(300, "AFTER-OVER", null, null), 2);
        assertEquals(1L, next.entries().get(0).receiptNo(),
                "a refused payment must not consume a receipt number");
    }

    /** Posting against a stale version is refused with 412 and allocates nothing. */
    @Test
    void aStaleVersionIsRefusedWithoutChangingBalanceOrAllocatingAReceipt() {
        String id = newIssued(1300);
        ApiException failure = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.PAYMENT, entry(300, "STALE-REF", null, null), 1));
        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        assertEquals(1300L, service.get(id).invoice().balanceMinor());
        assertEquals(0, service.get(id).entries().size());

        View next = service.postEntry(id, EntryType.PAYMENT, entry(300, "FRESH-REF", null, null), 2);
        assertEquals(1L, next.entries().get(0).receiptNo(),
                "a refused stale posting must not consume a receipt number");
    }

    /** A subject without an active finance membership cannot change a balance. */
    @Test
    void aSubjectWithoutFinanceMembershipCannotChangeTheBalance() {
        String id = newIssued(1300);
        TenantContext.set(new TenantContext(ORG, OUTSIDER, "FINANCE", "corr-2"));
        ApiException denied = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.PAYMENT, entry(300, "OUTSIDER-REF", null, null), 2));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());

        // Restore the authorized synthetic actor to read back the committed state.
        TenantContext.set(new TenantContext(ORG, ACTOR, "FINANCE", "corr-1"));
        View after = service.get(id);
        assertEquals(1300L, after.invoice().balanceMinor());
        assertEquals(0, after.entries().size());
    }

    /** Test-only in-memory repository that drives the real service without PostgreSQL. */
    private static final class LedgerFakeRepository extends FinanceRepository {

        private final Map<String, Invoice> invoices = new LinkedHashMap<>();
        private final Map<String, List<InvoiceItem>> items = new LinkedHashMap<>();
        private final Map<String, List<LedgerEntry>> entries = new LinkedHashMap<>();
        private final Map<String, Replay> byReference = new LinkedHashMap<>();
        private long receiptCounter;

        LedgerFakeRepository() {
            super(null);
        }

        @Override
        public boolean hasActiveMembership(String subject) {
            return Set.of(ACTOR, ADMIN).contains(subject);
        }

        @Override
        public List<String> activeRoles(String subject) {
            return ADMIN.equals(subject) ? List.of("tenant_admin") : List.of("finance");
        }

        @Override
        public boolean patientExists(String patientId) {
            return PATIENT.equals(patientId);
        }

        @Override
        public String insertInvoice(String patientId, String currency, String serviceReference, long totalMinor) {
            String id = FinanceInputs.newId();
            invoices.put(id, new Invoice(id, 1, patientId, currency, InvoiceState.DRAFT,
                    totalMinor, 0, 0, 0, totalMinor, serviceReference, CREATED, null, null, null));
            items.put(id, new ArrayList<>());
            entries.put(id, new ArrayList<>());
            return id;
        }

        @Override
        public void insertItem(String invoiceId, int lineNo, String description, int quantity,
                               long unitAmountMinor, long amountMinor) {
            items.get(invoiceId).add(new InvoiceItem(FinanceInputs.newId(), invoiceId, lineNo,
                    description, quantity, unitAmountMinor, amountMinor));
        }

        @Override
        public Optional<Invoice> findInvoice(String id) {
            return Optional.ofNullable(invoices.get(id));
        }

        @Override
        public Optional<Invoice> loadForUpdate(String id) {
            return findInvoice(id);
        }

        @Override
        public List<InvoiceItem> items(String invoiceId) {
            return List.copyOf(items.getOrDefault(invoiceId, List.of()));
        }

        @Override
        public List<LedgerEntry> entries(String invoiceId) {
            return List.copyOf(entries.getOrDefault(invoiceId, List.of()));
        }

        @Override
        public int issueInvoice(String id, int expectedVersion, long totalMinor) {
            Invoice current = invoices.get(id);
            invoices.put(id, new Invoice(current.id(), current.version() + 1, current.patientId(),
                    current.currency(), InvoiceState.ISSUED, current.totalMinor(), current.paidMinor(),
                    current.refundedMinor(), current.creditedMinor(), totalMinor,
                    current.serviceReference(), current.createdAt(), ISSUED_AT,
                    current.voidReason(), current.voidedBySubject()));
            return 1;
        }

        @Override
        public long nextReceiptNo() {
            return ++receiptCounter;
        }

        @Override
        public Optional<Replay> findByReference(String reference) {
            return Optional.ofNullable(byReference.get(reference));
        }

        @Override
        public void insertEntry(String invoiceId, int invoiceVersion, EntryType type, long amountMinor,
                                 String currency, String reference, String reason, String approverSubject,
                                 long receiptNo, String digest) {
            LedgerEntry entry = new LedgerEntry(FinanceInputs.newId(), invoiceId, invoiceVersion, type,
                    amountMinor, currency, reference, reason, approverSubject, receiptNo, ACTOR, OCCURRED);
            entries.get(invoiceId).add(entry);
            byReference.put(reference, new Replay(digest, entry));
        }

        @Override
        public int updateInvoice(String id, int expectedVersion, long paidMinor, long refundedMinor,
                                 long creditedMinor, long balanceMinor, InvoiceState state) {
            Invoice current = invoices.get(id);
            invoices.put(id, new Invoice(current.id(), current.version() + 1, current.patientId(),
                    current.currency(), state, current.totalMinor(), paidMinor, refundedMinor,
                    creditedMinor, balanceMinor, current.serviceReference(), current.createdAt(),
                    current.issuedAt(), current.voidReason(), current.voidedBySubject()));
            return 1;
        }

        @Override
        public boolean hasEntries(String invoiceId) {
            return !entries.getOrDefault(invoiceId, List.of()).isEmpty();
        }

        @Override
        public int voidInvoice(String id, int expectedVersion, String reason, String approverSubject) {
            Invoice current = invoices.get(id);
            invoices.put(id, new Invoice(current.id(), current.version() + 1, current.patientId(),
                    current.currency(), InvoiceState.VOID, current.totalMinor(), current.paidMinor(),
                    current.refundedMinor(), current.creditedMinor(), current.balanceMinor(),
                    current.serviceReference(), current.createdAt(), current.issuedAt(), reason, approverSubject));
            return 1;
        }
    }
}
