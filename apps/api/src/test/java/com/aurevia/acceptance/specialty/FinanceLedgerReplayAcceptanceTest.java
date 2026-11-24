package com.aurevia.acceptance.specialty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
import com.aurevia.finance.FinanceActions;
import com.aurevia.finance.FinanceContract.CreateInvoiceRequest;
import com.aurevia.finance.FinanceContract.EntryRequest;
import com.aurevia.finance.FinanceContract.EntryType;
import com.aurevia.finance.FinanceContract.Invoice;
import com.aurevia.finance.FinanceContract.InvoiceItem;
import com.aurevia.finance.FinanceContract.InvoiceState;
import com.aurevia.finance.FinanceContract.LedgerEntry;
import com.aurevia.finance.FinanceContract.LineRequest;
import com.aurevia.finance.FinanceContract.View;
import com.aurevia.finance.FinanceInputs;
import com.aurevia.finance.FinanceRepository;
import com.aurevia.finance.FinanceRepository.Replay;
import com.aurevia.finance.FinanceService;
import com.aurevia.tenant.TenantContext;

/**
 * Gate G4 acceptance for self-pay finance replay.
 *
 * <p>Requirements proved: FIN-01 (itemized invoices in fixed-precision minor
 * units, immutable linked payment and refund entries, deduplicated references
 * and numbered receipts) and G4 (payment and refund entries reproduce the
 * balance on replay). Replaying a recorded reference returns the original
 * committed balance, version, ledger trail and receipt numbers without posting
 * money twice. All identifiers, subjects and amounts are synthetic.</p>
 */
class FinanceLedgerReplayAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ACTOR = "finance-4444";
    private static final String ADMIN = "admin-4444";
    private static final Instant CREATED = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant ISSUED_AT = Instant.parse("2026-11-20T10:01:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-11-20T10:02:00Z");

    private LedgerReplayFakeRepository repository;
    private AuditAppender audit;
    private FinanceService service;

    @BeforeEach
    void setUp() {
        repository = new LedgerReplayFakeRepository();
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new FinanceService(repository, new FinanceActions(), transactions, audit);
        TenantContext.set(new TenantContext(ORG, ACTOR, "FINANCE", "corr-g4-finance-replay"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void paymentAndRefundReplaysReproduceTheCommittedBalance() {
        // FIN-01 / G4: replaying the recorded references reproduces the exact
        // balance, version, ledger trail and receipt numbers without a second post.
        String id = service.create(new CreateInvoiceRequest(PATIENT, "USD", "ENCOUNTER-G4",
                List.of(new LineRequest("consultation", 1, 1300L)))).invoice().id();
        service.issue(id, 1);

        View paid = service.postEntry(id, EntryType.PAYMENT,
                new EntryRequest(300L, "G4-PAY-1", "desk", null), 2);
        View refunded = service.postEntry(id, EntryType.REFUND,
                new EntryRequest(100L, "G4-REF-1", "patient cancellation", ADMIN), 3);

        assertEquals(1000L, paid.invoice().balanceMinor());
        assertEquals(1100L, refunded.invoice().balanceMinor());
        assertEquals(300L, refunded.invoice().paidMinor());
        assertEquals(100L, refunded.invoice().refundedMinor());
        assertEquals(InvoiceState.PARTIALLY_PAID, refunded.invoice().state());
        assertEquals(2, refunded.entries().size());
        assertEquals(1L, paid.entries().get(0).receiptNo());
        assertEquals(2L, refunded.entries().get(1).receiptNo());
        int committedVersion = refunded.invoice().version();

        // Replay of the original payment, even with a stale version, is a no-op:
        // no second entry, no second receipt and no balance or version change.
        View paymentReplay = service.postEntry(id, EntryType.PAYMENT,
                new EntryRequest(300L, "G4-PAY-1", "desk", null), 1);
        assertEquals(refunded.invoice().balanceMinor(), paymentReplay.invoice().balanceMinor());
        assertEquals(refunded.invoice().paidMinor(), paymentReplay.invoice().paidMinor());
        assertEquals(refunded.invoice().refundedMinor(), paymentReplay.invoice().refundedMinor());
        assertEquals(committedVersion, paymentReplay.invoice().version());
        assertEquals(2, paymentReplay.entries().size());
        assertEquals(refunded.entries(), paymentReplay.entries());

        // Replay of the original refund reproduces the same committed balance.
        View refundReplay = service.postEntry(id, EntryType.REFUND,
                new EntryRequest(100L, "G4-REF-1", "patient cancellation", ADMIN), 1);
        assertEquals(1100L, refundReplay.invoice().balanceMinor());
        assertEquals(committedVersion, refundReplay.invoice().version());
        assertEquals(2, refundReplay.entries().size());
        assertEquals(refunded.entries(), refundReplay.entries());
        assertEquals(refunded.invoice().balanceMinor(), service.get(id).invoice().balanceMinor());

        verify(audit).append(TenantContext.current(), "FINANCE_PAYMENT_RECORD_REPLAY",
                "FINANCE_PAYMENT_RECORD_REPLAY", "FINANCE_INVOICE", "FINANCE", id, "SUCCESS");
    }

    @Test
    void aReferenceReusedWithADifferentPayloadDoesNotReproduceABalance() {
        // FIN-01: a stable reference bound to a different payload is a conflict,
        // not an idempotent replay, so the committed balance is left untouched.
        String id = service.create(new CreateInvoiceRequest(PATIENT, "USD", "ENCOUNTER-G4-2",
                List.of(new LineRequest("consultation", 1, 1300L)))).invoice().id();
        service.issue(id, 1);
        service.postEntry(id, EntryType.PAYMENT,
                new EntryRequest(300L, "G4-SHARED", null, null), 2);

        ApiException conflict = assertThrows(ApiException.class, () -> service.postEntry(
                id, EntryType.REFUND,
                new EntryRequest(300L, "G4-SHARED", "different payload", ADMIN), 3));
        assertEquals(409, conflict.status());
        assertEquals("REFERENCE_CONFLICT", conflict.code());
        assertEquals(1000L, service.get(id).invoice().balanceMinor());
        assertEquals(1, service.get(id).entries().size());
    }

    /** Database-free FinanceRepository fixture with the same observable contract. */
    private static final class LedgerReplayFakeRepository extends FinanceRepository {
        private final Map<String, Invoice> invoices = new LinkedHashMap<>();
        private final Map<String, List<InvoiceItem>> items = new LinkedHashMap<>();
        private final Map<String, List<LedgerEntry>> entries = new LinkedHashMap<>();
        private final Map<String, Replay> byReference = new LinkedHashMap<>();
        private long receiptCounter;

        LedgerReplayFakeRepository() {
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
        public String insertInvoice(String patientId, String currency, String serviceReference,
                                    long totalMinor) {
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
            if (current.version() != expectedVersion) {
                return 0;
            }
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
            if (current.version() != expectedVersion) {
                return 0;
            }
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
                    current.serviceReference(), current.createdAt(), current.issuedAt(), reason,
                    approverSubject));
            return 1;
        }
    }
}
