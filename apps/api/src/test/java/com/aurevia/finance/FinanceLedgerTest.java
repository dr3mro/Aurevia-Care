package com.aurevia.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.finance.FinanceContract.CreateInvoiceRequest;
import com.aurevia.finance.FinanceContract.EntryRequest;
import com.aurevia.finance.FinanceContract.EntryType;
import com.aurevia.finance.FinanceContract.Invoice;
import com.aurevia.finance.FinanceContract.InvoiceItem;
import com.aurevia.finance.FinanceContract.InvoiceState;
import com.aurevia.finance.FinanceContract.LedgerEntry;
import com.aurevia.finance.FinanceContract.LineRequest;
import com.aurevia.finance.FinanceContract.View;
import com.aurevia.finance.FinanceContract.VoidRequest;
import com.aurevia.finance.FinanceRepository.Replay;
import com.aurevia.tenant.TenantContext;

/**
 * Fast, database-free acceptance for the FIN-01 immutable ledger: fixed precision,
 * partial payments in any order, overpayment and excess-refund rejection, and a
 * refusal to void once money is posted. A test-only in-memory repository drives
 * the real {@link FinanceService} so these branches run in the fast gate; the
 * PostgreSQL and HTTP behaviour is covered by the finance integration suite.
 */
class FinanceLedgerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ACTOR = "finance-1111";
    private static final String ADMIN = "admin-1111";
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

    private View createInvoice(String currency, LineRequest... lines) {
        return service.create(new CreateInvoiceRequest(PATIENT, currency, "ENCOUNTER-1", List.of(lines)));
    }

    private String newIssued(long total) {
        String id = createInvoice("USD", new LineRequest("fee", 1, total)).invoice().id();
        service.issue(id, 1);
        return id;
    }

    private static EntryRequest entry(long amount, String reference, String reason, String approver) {
        return new EntryRequest(amount, reference, reason, approver);
    }

    @Test
    void fixedPrecisionMinorUnitsNeverRoundLineAmountsOrTotals() {
        View created = createInvoice("USD",
                new LineRequest("three thirds", 3, 333L),
                new LineRequest("seven parts", 7, 143L));
        // Integer minor units multiply exactly: 3 x 333 = 999 (not a rounded 1000).
        assertEquals(999L, created.items().get(0).amountMinor());
        assertEquals(1001L, created.items().get(1).amountMinor());
        assertEquals(2000L, created.invoice().totalMinor());
        assertEquals(2000L, created.invoice().balanceMinor());
        assertEquals(InvoiceState.DRAFT, created.invoice().state());
        for (InvoiceItem line : created.items()) {
            assertEquals(line.quantity() * line.unitAmountMinor(), line.amountMinor());
        }

        ApiException multiplyOverflow = assertThrows(ApiException.class, () -> createInvoice("USD",
                new LineRequest("multiply overflow", 2, Long.MAX_VALUE)));
        assertEquals(422, multiplyOverflow.status());
        assertEquals("AMOUNT_OVERFLOW", multiplyOverflow.code());

        long half = Long.MAX_VALUE / 2 + 1;
        ApiException sumOverflow = assertThrows(ApiException.class, () -> createInvoice("USD",
                new LineRequest("sum overflow a", 1, half),
                new LineRequest("sum overflow b", 1, half)));
        assertEquals(422, sumOverflow.status());
        assertEquals("AMOUNT_OVERFLOW", sumOverflow.code());
    }

    @Test
    void partialPaymentsInAnyOrderConvergeToPaid() {
        String ascending = createInvoice("SAR", new LineRequest("fee", 1, 1300L)).invoice().id();
        String descending = createInvoice("SAR", new LineRequest("fee", 1, 1300L)).invoice().id();
        service.issue(ascending, 1);
        service.issue(descending, 1);

        View a1 = service.postEntry(ascending, EntryType.PAYMENT, entry(300, "A-1", null, null), 2);
        assertEquals(InvoiceState.PARTIALLY_PAID, a1.invoice().state());
        assertEquals(1000L, a1.invoice().balanceMinor());
        assertEquals(300L, a1.invoice().paidMinor());
        View a2 = service.postEntry(ascending, EntryType.PAYMENT, entry(1000, "A-2", null, null), 3);
        assertEquals(InvoiceState.PAID, a2.invoice().state());
        assertEquals(0L, a2.invoice().balanceMinor());
        assertEquals(1300L, a2.invoice().paidMinor());

        View b1 = service.postEntry(descending, EntryType.PAYMENT, entry(700, "B-1", null, null), 2);
        LedgerEntry first = b1.entries().get(0);
        View b2 = service.postEntry(descending, EntryType.PAYMENT, entry(600, "B-2", null, null), 3);
        assertEquals(InvoiceState.PAID, b2.invoice().state());
        assertEquals(0L, b2.invoice().balanceMinor());
        assertEquals(1300L, b2.invoice().paidMinor());

        // Every entry is a linked immutable snapshot of the invoice currency and version.
        assertEquals(2, b2.entries().size());
        assertEquals(descending, b2.entries().get(0).invoiceId());
        assertEquals(2, b2.entries().get(0).invoiceVersion());
        assertEquals(3, b2.entries().get(1).invoiceVersion());
        assertEquals("SAR", b2.entries().get(0).currency());
        assertEquals("SAR", b2.entries().get(1).currency());
        assertEquals(b2.entries().get(0).receiptNo() + 1, b2.entries().get(1).receiptNo());

        // Posting a later entry never rewrites an earlier one, and the trail is frozen.
        assertEquals(first, b2.entries().get(0));
        assertEquals(700L, b2.entries().get(0).amountMinor());
        assertThrows(UnsupportedOperationException.class, () -> b2.entries().clear());
    }

    @Test
    void overpaymentIsRejectedWithoutPostingAnEntry() {
        String id = newIssued(1300);
        ApiException failure = assertThrows(ApiException.class,
                () -> service.postEntry(id, EntryType.PAYMENT, entry(1301, "OVER", null, null), 2));
        assertEquals(422, failure.status());
        assertEquals("INSUFFICIENT_BALANCE", failure.code());
        View after = service.get(id);
        assertEquals(1300L, after.invoice().balanceMinor());
        assertEquals(0L, after.invoice().paidMinor());
        assertEquals(0, after.entries().size());
    }

    @Test
    void refundAboveNetPaidIsRejected() {
        String id = newIssued(1300);
        service.postEntry(id, EntryType.PAYMENT, entry(500, "PAY-500", null, null), 2);

        ApiException first = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.REFUND, entry(501, "REF-HIGH", "too much", ADMIN), 3));
        assertEquals(422, first.status());
        assertEquals("EXCESS_REFUND", first.code());

        View partial = service.postEntry(id, EntryType.REFUND, entry(300, "REF-OK", "partial", ADMIN), 3);
        assertEquals(300L, partial.invoice().refundedMinor());
        assertEquals(1100L, partial.invoice().balanceMinor());
        ApiException second = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.REFUND, entry(201, "REF-HIGH-2", "too much", ADMIN), 4));
        assertEquals("EXCESS_REFUND", second.code());
        assertEquals(1100L, service.get(id).invoice().balanceMinor());
    }

    @Test
    void voidAfterPaymentIsRefused() {
        String partial = newIssued(1300);
        service.postEntry(partial, EntryType.PAYMENT, entry(300, "VP-1", null, null), 2);
        ApiException partialVoid = assertThrows(ApiException.class,
                () -> service.voidInvoice(partial, new VoidRequest("changed mind", ADMIN), 3));
        assertEquals(409, partialVoid.status());
        assertEquals("INVALID_TRANSITION", partialVoid.code());

        String reversed = newIssued(1300);
        service.postEntry(reversed, EntryType.PAYMENT, entry(1300, "VP-2", null, null), 2);
        service.postEntry(reversed, EntryType.REFUND, entry(1300, "VR-2", "full reversal", ADMIN), 3);
        assertEquals(InvoiceState.ISSUED, service.get(reversed).invoice().state());
        // Even after a full refund restores 'issued', the posted ledger forbids void.
        ApiException postedVoid = assertThrows(ApiException.class,
                () -> service.voidInvoice(reversed, new VoidRequest("after posting", ADMIN), 4));
        assertEquals(422, postedVoid.status());
        assertEquals("VOID_AFTER_POSTING", postedVoid.code());
        assertEquals(InvoiceState.ISSUED, service.get(reversed).invoice().state());
        assertEquals(2, service.get(reversed).entries().size());
    }

    @Test
    void approvedCreditReducesBalanceAndRequiresReasonAndApprover() {
        String id = newIssued(1300);
        ApiException noReason = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.CREDIT, entry(100, "CR-NO-REASON", null, ADMIN), 2));
        assertEquals(422, noReason.status());
        ApiException noApprover = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.CREDIT, entry(100, "CR-NO-APPROVER", "write-off", null), 2));
        assertEquals(422, noApprover.status());
        ApiException self = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.CREDIT, entry(100, "CR-SELF", "write-off", ACTOR), 2));
        assertEquals(403, self.status());
        assertEquals("SEPARATION_OF_DUTIES", self.code());

        View credited = service.postEntry(id, EntryType.CREDIT,
                entry(300, "CR-OK", "approved write-off", ADMIN), 2);
        assertEquals(1000L, credited.invoice().balanceMinor());
        assertEquals(300L, credited.invoice().creditedMinor());
        assertEquals(InvoiceState.PARTIALLY_PAID, credited.invoice().state());
        assertEquals("approved write-off", credited.entries().get(0).reason());
        assertEquals(ADMIN, credited.entries().get(0).approverSubject());

        ApiException over = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.CREDIT, entry(1001, "CR-OVER", "too much", ADMIN), 3));
        assertEquals("INSUFFICIENT_BALANCE", over.code());
    }

    @Test
    void everyLinkedEntrySnapshotsTheInvoicesSingleCurrency() {
        String riyal = createInvoice("SAR", new LineRequest("fee", 1, 1300L)).invoice().id();
        service.issue(riyal, 1);
        assertEquals("SAR", service.get(riyal).invoice().currency());
        service.postEntry(riyal, EntryType.PAYMENT, entry(500, "CUR-PAY", null, null), 2);
        service.postEntry(riyal, EntryType.REFUND, entry(100, "CUR-REF", "partial", ADMIN), 3);
        View withCredit = service.postEntry(riyal, EntryType.CREDIT,
                entry(200, "CUR-CR", "write-off", ADMIN), 4);
        assertEquals(3, withCredit.entries().size());
        for (LedgerEntry entry : withCredit.entries()) {
            assertEquals("SAR", entry.currency());
        }
        assertEquals("SAR", withCredit.entries().get(1).currency(), "a refund snapshots the invoice currency");
        assertEquals("SAR", withCredit.entries().get(2).currency(), "a credit snapshots the invoice currency");

        String dollar = newIssued(1300);
        View dollarView = service.postEntry(dollar, EntryType.PAYMENT, entry(100, "CUR-USD", null, null), 2);
        assertEquals("USD", dollarView.invoice().currency());
        assertEquals("USD", dollarView.entries().get(0).currency(),
                "currency belongs to the invoice, not to a global constant");
    }

    @Test
    void reversalsAndVoidsRequireAReason() {
        String id = newIssued(1300);
        service.postEntry(id, EntryType.PAYMENT, entry(500, "R-PAY", null, null), 2);

        ApiException refund = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.REFUND, entry(100, "R-NO-REASON", null, ADMIN), 3));
        assertEquals(422, refund.status());
        assertEquals("INVALID_REASON", refund.code());
        ApiException blankRefund = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.REFUND, entry(100, "R-BLANK", "   ", ADMIN), 3));
        assertEquals(422, blankRefund.status());
        assertEquals("INVALID_REASON", blankRefund.code());
        assertEquals(500L, service.get(id).invoice().paidMinor(), "a rejected reversal changes no money");

        String draft = createInvoice("USD", new LineRequest("fee", 1, 1300L)).invoice().id();
        ApiException voidMissing = assertThrows(ApiException.class,
                () -> service.voidInvoice(draft, new VoidRequest(null, ADMIN), 1));
        assertEquals(422, voidMissing.status());
        assertEquals("INVALID_REASON", voidMissing.code());
        assertEquals(InvoiceState.DRAFT, service.get(draft).invoice().state());
    }

    @Test
    void aDraftCanBeVoidedOnceWithAnIndependentApprover() {
        String id = createInvoice("USD", new LineRequest("fee", 1, 1300L)).invoice().id();
        View voided = service.voidInvoice(id, new VoidRequest("cancelled", ADMIN), 1);
        assertEquals(InvoiceState.VOID, voided.invoice().state());
        assertEquals("cancelled", voided.invoice().voidReason());
        assertEquals(ADMIN, voided.invoice().voidedBySubject());

        ApiException again = assertThrows(ApiException.class,
                () -> service.voidInvoice(id, new VoidRequest("again", ADMIN), 2));
        assertEquals(409, again.status());
        assertEquals("INVALID_TRANSITION", again.code());
    }

    @Test
    void replayIsANoOpAndAChangedPayloadWithTheSameReferenceConflicts() {
        String id = newIssued(1300);
        View first = service.postEntry(id, EntryType.PAYMENT, entry(300, "REPLAY-1", "desk", null), 2);
        View replay = service.postEntry(id, EntryType.PAYMENT, entry(300, "REPLAY-1", "desk", null), 3);
        assertEquals(first.invoice().balanceMinor(), replay.invoice().balanceMinor());
        assertEquals(1, replay.entries().size());
        assertEquals(first.invoice().version(), replay.invoice().version(),
                "a replay changes no balance and allocates no version");

        ApiException conflict = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.PAYMENT, entry(300, "REPLAY-1", "different desk", null), 2));
        assertEquals(409, conflict.status());
        assertEquals("REFERENCE_CONFLICT", conflict.code());
        assertEquals(1000L, service.get(id).invoice().balanceMinor());
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
