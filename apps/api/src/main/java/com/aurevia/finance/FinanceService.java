package com.aurevia.finance;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.finance.FinanceContract.CreateInvoiceRequest;
import com.aurevia.finance.FinanceContract.EntryRequest;
import com.aurevia.finance.FinanceContract.EntryType;
import com.aurevia.finance.FinanceContract.Invoice;
import com.aurevia.finance.FinanceContract.InvoiceState;
import com.aurevia.finance.FinanceContract.LineRequest;
import com.aurevia.finance.FinanceContract.Reconciliation;
import com.aurevia.finance.FinanceContract.VoidRequest;
import com.aurevia.finance.FinanceContract.View;
import com.aurevia.finance.FinanceRepository.InvoiceRow;
import com.aurevia.finance.FinanceRepository.Replay;
import com.aurevia.tenant.TenantContext;

/**
 * FIN-01 self-pay finance workflow.
 *
 * <p>Each command runs in one tenant-guarded transaction: authorization is
 * re-derived from the authoritative folds, the version and state preconditions
 * are checked under a row lock, and the invoice change, immutable ledger or
 * item rows and audit event commit together. Payments, refunds and credits are
 * separate facts; a repeated reference with the same payload returns the
 * original outcome without a second balance change or receipt, while a repeated
 * reference with a different payload is rejected. Every reversal or void needs
 * a reason and an independent, approved approver.</p>
 */
@Service
public class FinanceService {

    private static final String SCOPE = "FINANCE";
    private static final String RESOURCE_TYPE = "FINANCE_INVOICE";
    private static final int MAX_DESCRIPTION = 120;
    private static final int MAX_SERVICE_REFERENCE = 200;
    private static final Pattern CURSOR = Pattern.compile(
            "(.+):([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final List<String> STATES = Arrays.stream(InvoiceState.values())
            .map(InvoiceState::wire).toList();

    private final FinanceRepository repository;
    private final FinanceActions actions;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded finance repository
     * @param actions      bounded action matrix
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     */
    public FinanceService(FinanceRepository repository, FinanceActions actions,
                          TransactionTemplate transactions, AuditAppender audit) {
        this.repository = repository;
        this.actions = actions;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Create an itemized draft invoice.
     *
     * @param request creation command with one or more lines
     * @return the created invoice with its lines
     * @throws ApiException on invalid input, denial or an invisible patient
     */
    public View create(CreateInvoiceRequest request) {
        String patientId = FinanceInputs.uuid(request.patientId(), "patientId");
        String currency = FinanceInputs.currency(request.currency());
        String serviceReference = FinanceInputs.optionalText(
                request.serviceReference(), "serviceReference", MAX_SERVICE_REFERENCE);
        List<LineRequest> lines = request.items();
        if (lines == null || lines.isEmpty()) {
            throw new ApiException(422, "INVALID_ITEMS", "an invoice needs at least one itemized line");
        }
        List<LineRequest> normalized = new ArrayList<>();
        long total = 0;
        for (LineRequest line : lines) {
            String description = FinanceInputs.text(line.description(), "description", MAX_DESCRIPTION);
            int quantity = FinanceInputs.positiveQuantity(line.quantity());
            long unit = FinanceInputs.nonNegativeAmount(line.unitAmountMinor(), "unitAmountMinor");
            long amount = FinanceInputs.multiply(quantity, unit, "amountMinor");
            total = FinanceInputs.add(total, amount);
            normalized.add(new LineRequest(description, quantity, unit));
        }
        final long invoiceTotal = total;
        return execute(FinanceActions.CREATE, "NEW", sink -> {
            if (!repository.patientExists(patientId)) {
                throw new ApiException(404, "NOT_FOUND", "patient not found");
            }
            String id = repository.insertInvoice(patientId, currency, serviceReference, invoiceTotal);
            for (int index = 0; index < normalized.size(); index++) {
                final int lineNo = index + 1;
                LineRequest line = normalized.get(index);
                repository.insertItem(id, lineNo, line.description(), line.quantity(),
                        line.unitAmountMinor(), FinanceInputs.multiply(
                                line.quantity(), line.unitAmountMinor(), "amountMinor"));
            }
            success(FinanceActions.CREATE, id);
            return view(id);
        });
    }

    /**
     * Issue a draft invoice so its total becomes an outstanding balance.
     *
     * @param id              invoice identifier
     * @param expectedVersion version read by the caller
     * @return the issued invoice
     * @throws ApiException on denial, stale version or an already posted invoice
     */
    public View issue(String id, int expectedVersion) {
        FinanceInputs.uuid(id, "id");
        return execute(FinanceActions.ISSUE, id, sink -> {
            Invoice current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireState(current, InvoiceState.DRAFT);
            // The row is locked and the version precondition already matched,
            // so this guarded update cannot lose a concurrent change.
            repository.issueInvoice(id, expectedVersion, current.totalMinor());
            success(FinanceActions.ISSUE, id);
            return view(id);
        });
    }

    /**
     * Post a payment, approved credit or approved refund to an issued invoice.
     *
     * @param id              invoice identifier
     * @param type            entry type selected by the route
     * @param request         entry command
     * @param expectedVersion version read by the caller
     * @return the invoice and its full ledger trail
     * @throws ApiException on denial, stale version, invalid payload or an over-balance entry
     */
    public View postEntry(String id, EntryType type, EntryRequest request, int expectedVersion) {
        FinanceInputs.uuid(id, "id");
        long amount = FinanceInputs.positiveAmount(request.amountMinor(), "amountMinor");
        String reference = FinanceInputs.reference(request.reference());
        String reason = type == EntryType.PAYMENT
                ? FinanceInputs.optionalReason(request.reason(), "reason")
                : FinanceInputs.requiredReason(request.reason(), "reason");
        String approver = type == EntryType.PAYMENT
                ? null : FinanceInputs.subject(request.approverSubject(), "approverSubject");
        String action = actionFor(type);
        return execute(action, id, sink -> {
            Invoice current = repository.loadForUpdate(id).orElseThrow(notFound());
            String digest = FinanceInputs.digest(id, type, amount, current.currency(), reason, approver);
            var prior = repository.findByReference(reference);
            if (prior.isPresent()) {
                Replay replay = prior.get();
                if (!replay.digest().equals(digest)) {
                    conflict(sink, action, id, 409, "REFERENCE_CONFLICT",
                            "the reference was used with a different payload");
                    return null;
                }
                // A durable replay reuses the original outcome: no second entry,
                // no second receipt and no change to the committed balance.
                success(action + "_REPLAY", id);
                return view(id);
            }
            requireVersion(current, expectedVersion);
            if (current.state() == InvoiceState.DRAFT || current.state() == InvoiceState.VOID) {
                throw new ApiException(409, "INVALID_TRANSITION",
                        "entries can only be posted to an issued invoice");
            }
            if (type != EntryType.PAYMENT) {
                ApiException approval = approvalFailure(approver, context());
                if (approval != null) {
                    denied(sink, action, id, approval);
                    return null;
                }
            }
            long paid = current.paidMinor();
            long refunded = current.refundedMinor();
            long credited = current.creditedMinor();
            long balance = current.balanceMinor();
            if (type == EntryType.PAYMENT) {
                if (amount > balance) {
                    throw new ApiException(422, "INSUFFICIENT_BALANCE", "the payment exceeds the balance");
                }
                paid += amount;
                balance -= amount;
            } else if (type == EntryType.CREDIT) {
                if (amount > balance) {
                    throw new ApiException(422, "INSUFFICIENT_BALANCE", "the credit exceeds the balance");
                }
                credited += amount;
                balance -= amount;
            } else {
                // The only remaining entry type is a refund.
                if (amount > paid - refunded) {
                    throw new ApiException(422, "EXCESS_REFUND", "the refund exceeds the net collected amount");
                }
                refunded += amount;
                balance += amount;
            }
            InvoiceState target = targetState(balance, current.totalMinor());
            long receiptNo = repository.nextReceiptNo();
            repository.insertEntry(id, expectedVersion, type, amount, current.currency(),
                    reference, reason, approver, receiptNo, digest);
            // The locked row and matched version make this a safe compare-and-set.
            repository.updateInvoice(id, expectedVersion, paid, refunded, credited, balance, target);
            success(action, id);
            return view(id);
        });
    }

    /**
     * Void an unposted invoice with a reason and an independent approver.
     *
     * @param id              invoice identifier
     * @param request         void command
     * @param expectedVersion version read by the caller
     * @return the void invoice
     * @throws ApiException on denial, stale version, an already void invoice or a posted/paid invoice
     */
    public View voidInvoice(String id, VoidRequest request, int expectedVersion) {
        FinanceInputs.uuid(id, "id");
        String reason = FinanceInputs.requiredReason(request.reason(), "reason");
        String approver = FinanceInputs.subject(request.approverSubject(), "approverSubject");
        return execute(FinanceActions.VOID, id, sink -> {
            Invoice current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            // VOID is terminal: a second void must not overwrite the recorded
            // reason and independent approver of the original decision.
            if (current.state() == InvoiceState.VOID) {
                throw new ApiException(409, "INVALID_TRANSITION", "invoice is already void");
            }
            if (!current.state().canTransitionTo(InvoiceState.VOID)) {
                throw new ApiException(409, "INVALID_TRANSITION",
                        "only a draft or an issued unposted invoice can be voided");
            }
            if (repository.hasEntries(id)) {
                conflict(sink, FinanceActions.VOID, id, 422, "VOID_AFTER_POSTING",
                        "reverse posted money with a refund instead of voiding");
                return null;
            }
            ApiException approval = approvalFailure(approver, context());
            if (approval != null) {
                denied(sink, FinanceActions.VOID, id, approval);
                return null;
            }
            repository.voidInvoice(id, expectedVersion, reason, approver);
            success(FinanceActions.VOID, id);
            return view(id);
        });
    }

    /**
     * Read one invoice with its lines and full immutable ledger trail.
     *
     * @param id invoice identifier
     * @return the invoice view
     * @throws ApiException on denial or an invisible invoice
     */
    public View get(String id) {
        FinanceInputs.uuid(id, "id");
        return execute(FinanceActions.READ, id, sink -> {
            View result = view(id);
            success(FinanceActions.READ, id);
            return result;
        });
    }

    /**
     * Read one cursor-paginated page of the active tenant's invoices.
     *
     * <p>The single-record read action is reused: a caller who may read one
     * invoice may list the same tenant's invoices. Input is validated before
     * any transaction and the page read is audited inside the guarded
     * transaction. One extra row is fetched to decide whether a next page
     * exists; its last returned row carries the opaque cursor.</p>
     *
     * @param cursor    opaque {@code <createdAt>:<id>} of the previous page's last row, or {@code null}
     * @param limit     page size, or {@code null} for the default of 50
     * @param status    optional exact invoice state filter
     * @param patientId optional exact owning patient identifier filter
     * @return one page with an opaque next cursor when more rows exist
     * @throws ApiException on invalid input or denial
     */
    public InvoicePage list(String cursor, String limit, String status, String patientId) {
        InvoiceCursor parsedCursor = parseCursor(cursor);
        int parsedLimit = parseLimit(limit);
        String parsedStatus = parseStatus(status);
        String parsedPatientId = parsePatientId(patientId);
        return execute(FinanceActions.READ, "ALL", sink -> {
            List<InvoiceRow> rows = repository.query(
                    parsedCursor == null ? null : parsedCursor.createdAt(),
                    parsedCursor == null ? null : parsedCursor.id(),
                    parsedStatus, parsedPatientId, parsedLimit + 1);
            boolean hasMore = rows.size() > parsedLimit;
            List<InvoiceRow> pageRows = hasMore ? rows.subList(0, parsedLimit) : rows;
            String next = null;
            if (hasMore) {
                InvoiceRow last = pageRows.get(pageRows.size() - 1);
                next = last.createdAt() + ":" + last.invoice().id();
            }
            success(FinanceActions.READ, "ALL");
            return new InvoicePage(pageRows.stream()
                    .map(row -> new View(row.invoice(), List.of(), List.of())).toList(), next);
        });
    }

    private InvoiceCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        Matcher matcher = CURSOR.matcher(cursor);
        if (!matcher.matches()) {
            throw new ApiException(422, "INVALID_CURSOR", "cursor must be <createdAt>:<id>");
        }
        String canonical;
        try {
            canonical = Instant.parse(matcher.group(1)).toString();
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_CURSOR", "cursor must be <createdAt>:<id>");
        }
        if (!cursor.equals(canonical + ":" + matcher.group(2))) {
            throw new ApiException(422, "INVALID_CURSOR", "cursor must be canonical <createdAt>:<id>");
        }
        return new InvoiceCursor(canonical, matcher.group(2));
    }

    private int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(limit);
            if (value < 1 || value > MAX_LIMIT) {
                throw new NumberFormatException("limit out of range");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ApiException(422, "INVALID_LIMIT", "limit must be 1-200");
        }
    }

    private String parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (STATES.contains(status)) {
            return status;
        }
        throw new ApiException(422, "INVALID_STATUS", "status must be an invoice lifecycle state");
    }

    private String parsePatientId(String patientId) {
        if (patientId == null || patientId.isBlank()) {
            return null;
        }
        return FinanceInputs.uuid(patientId.trim(), "patientId");
    }

    /** Parsed opaque cursor: the canonical creation instant and the immutable row id. */
    private record InvoiceCursor(String createdAt, String id) {
    }

    /**
     * Build the finance-only daily reconciliation report.
     *
     * @return UTC daily totals by currency
     * @throws ApiException when the caller lacks reconciliation permission
     */
    public Reconciliation reconcile() {
        return execute(FinanceActions.RECONCILE, "REPORT", sink -> {
            Reconciliation report = new Reconciliation(repository.reconcile());
            success(FinanceActions.RECONCILE, "REPORT");
            return report;
        });
    }

    private <T> T execute(String action, String resourceId, Function<Sink, T> work) {
        Sink sink = new Sink();
        T value = transactions.execute(status -> {
            TenantContext context = context();
            ApiException denial = authorize(context, action, resourceId);
            if (denial != null) {
                sink.failure = denial;
                return null;
            }
            return work.apply(sink);
        });
        if (sink.failure != null) {
            throw sink.failure;
        }
        return value;
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())
                || repository.activeRoles(context.subject()).stream()
                        .noneMatch(role -> actions.allows(action, role))) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership with " + action + " permission");
        }
        return null;
    }

    private ApiException approvalFailure(String approver, TenantContext context) {
        if (approver.equals(context.subject())) {
            return new ApiException(403, "SEPARATION_OF_DUTIES", "an actor cannot approve their own entry");
        }
        if (!repository.hasActiveMembership(approver)
                || repository.activeRoles(approver).stream()
                        .noneMatch(role -> actions.allows(FinanceActions.APPROVE, role))) {
            return new ApiException(403, "INVALID_APPROVER", "the approver is not an active authorized member");
        }
        return null;
    }

    private String actionFor(EntryType type) {
        return switch (type) {
            case PAYMENT -> FinanceActions.PAYMENT;
            case REFUND -> FinanceActions.REFUND;
            case CREDIT -> FinanceActions.CREDIT;
        };
    }

    private InvoiceState targetState(long balance, long total) {
        if (balance == 0) {
            return InvoiceState.PAID;
        }
        if (balance == total) {
            return InvoiceState.ISSUED;
        }
        return InvoiceState.PARTIALLY_PAID;
    }

    private View view(String id) {
        Invoice invoice = repository.findInvoice(id).orElseThrow(notFound());
        return new View(invoice, repository.items(id), repository.entries(id));
    }

    private void requireVersion(Invoice current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw staleVersion();
        }
    }

    private void requireState(Invoice current, InvoiceState expected) {
        if (current.state() != expected) {
            throw new ApiException(409, "INVALID_TRANSITION",
                    "the invoice cannot be changed from " + current.state().wire());
        }
    }

    private void success(String action, String resourceId) {
        audit.append(context(), action, action, RESOURCE_TYPE, SCOPE, resourceId, "SUCCESS");
    }

    private void conflict(Sink sink, String action, String resourceId, int status, String code,
                          String message) {
        audit.append(context(), action, action, RESOURCE_TYPE, SCOPE, resourceId, "CONFLICT");
        sink.failure = new ApiException(status, code, message);
    }

    private void denied(Sink sink, String action, String resourceId, ApiException failure) {
        audit.append(context(), action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
        sink.failure = failure;
    }

    private TenantContext context() {
        return TenantContext.current();
    }

    private Supplier<ApiException> notFound() {
        return () -> new ApiException(404, "NOT_FOUND", "invoice not found");
    }

    private ApiException staleVersion() {
        return new ApiException(412, "STALE_VERSION", "the invoice changed since it was read");
    }

    /**
     * Carries a durable failure out of a committed transaction so its audit row
     * survives while the caller still receives an error.
     */
    private static final class Sink {
        private ApiException failure;
    }
}
