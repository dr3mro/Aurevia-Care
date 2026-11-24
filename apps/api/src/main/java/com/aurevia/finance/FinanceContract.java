package com.aurevia.finance;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for the FIN-01 self-pay finance slice.
 *
 * <p>The types here carry no persistence or transport concerns. All money is an
 * integer count of currency minor units, so precision is fixed and no floating
 * point value ever represents an amount. An invoice has exactly one currency and
 * every linked ledger entry snapshots it. The response types carry finance
 * fields only: item descriptions are catalogue-style non-clinical labels and no
 * clinical table is ever joined into a finance read.</p>
 */
public final class FinanceContract {

    private FinanceContract() {
    }

    /**
     * Lifecycle of a self-pay invoice. A paid invoice can be reversed only by a
     * linked refund, which moves it back to partially paid or, when the refund
     * covers the whole balance, to issued; void is terminal.
     */
    public enum InvoiceState {
        /** Editable draft; no money is owed until it is issued. */
        DRAFT("draft"),
        /** Issued and fully outstanding. */
        ISSUED("issued"),
        /** Some but not all of the balance has been settled. */
        PARTIALLY_PAID("partially_paid"),
        /** No balance remains outstanding. */
        PAID("paid"),
        /** Cancelled with a recorded reason and approval. */
        VOID("void");

        private final String wire;

        /**
         * Create a state with its stable wire key.
         *
         */
        InvoiceState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase state key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored state key.
         *
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static InvoiceState from(String value) {
            for (InvoiceState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown invoice state: " + value);
        }

        /**
         * Decide whether a transition is permitted. A no-op is allowed so a
         * balance recomputation does not need a state move.
         *
         * @return {@code true} when the transition is legal
         */
        public boolean canTransitionTo(InvoiceState next) {
            if (this == next) {
                return true;
            }
            return switch (this) {
                case DRAFT -> next == ISSUED || next == VOID;
                case ISSUED -> next == PARTIALLY_PAID || next == PAID || next == VOID;
                case PARTIALLY_PAID -> next == ISSUED || next == PAID;
                case PAID -> next == PARTIALLY_PAID || next == ISSUED;
                case VOID -> false;
            };
        }
    }

    /**
     * Append-only ledger entry vocabulary. Each type has a distinct effect on
     * the invoice's outstanding balance and is never merged into another type.
     */
    public enum EntryType {
        /** Money received from the patient; reduces the balance. */
        PAYMENT("payment"),
        /** Money returned to the patient; increases the balance. */
        REFUND("refund"),
        /** An approved adjustment that reduces the balance without money. */
        CREDIT("credit");

        private final String wire;

        /**
         * Create an entry type with its stable wire key.
         *
         */
        EntryType(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase entry key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored entry key.
         *
         * @return the matching entry type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static EntryType from(String value) {
            for (EntryType candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown ledger entry type: " + value);
        }
    }

    /**
     * One immutable itemized line.
     *
     * @return the immutable value
     */
    public record InvoiceItem(String id, String invoiceId, int lineNo, String description,
                              int quantity, long unitAmountMinor, long amountMinor) {
    }

    /**
     * Current projection of one invoice.
     *
     * @return the immutable value
     */
    public record Invoice(String id, int version, String patientId, String currency,
                          InvoiceState state, long totalMinor, long paidMinor, long refundedMinor,
                          long creditedMinor, long balanceMinor, String serviceReference,
                          Instant createdAt, Instant issuedAt, String voidReason,
                          String voidedBySubject) {
    }

    /**
     * One immutable, numbered finance ledger entry.
     *
     * @return the immutable value
     */
    public record LedgerEntry(String id, String invoiceId, int invoiceVersion, EntryType type,
                              long amountMinor, String currency, String reference, String reason,
                              String approverSubject, long receiptNo, String actorId,
                              Instant occurredAt) {
    }

    /**
     * An invoice together with its items and full ledger trail.
     *
     * @return the immutable value
     */
    public record View(Invoice invoice, List<InvoiceItem> items, List<LedgerEntry> entries) {
        /**
         * Defensively copy both lists.
         *
         */
        public View {
            items = List.copyOf(items);
            entries = List.copyOf(entries);
        }
    }

    /**
     * One day/currency row of the finance-only reconciliation report.
     *
     * @return the immutable value
     */
    public record DailyTotal(String day, String currency, long issuedMinor, long paymentMinor,
                             long refundMinor, long creditMinor, long netMinor, int entryCount) {
    }

    /**
     * Daily reconciliation totals. No clinical field is present.
     *
     * @return the immutable value
     */
    public record Reconciliation(List<DailyTotal> days) {
        /**
         * Defensively copy the rows.
         *
         */
        public Reconciliation {
            days = List.copyOf(days);
        }
    }

    /**
     * One itemized line supplied at invoice creation.
     *
     * @return the creation input
     */
    public record LineRequest(String description, Integer quantity, Long unitAmountMinor) {
    }

    /**
     * Create a draft invoice.
     *
     * @return the creation command
     */
    public record CreateInvoiceRequest(String patientId, String currency, String serviceReference,
                                       List<LineRequest> items) {
        /**
         * Defensively copy the item list so a request cannot be mutated after it
         * has been validated.
         *
         */
        public CreateInvoiceRequest {
            items = items == null ? null : List.copyOf(items);
        }
    }

    /**
     * Post a payment, credit or refund. The entry type comes from the route.
     *
     * @param amountMinor    positive magnitude in minor units
     * @param reference      stable tenant-unique reference used for replay dedup
     * @param reason         required for credits and refunds, optional for payments
     * @param approverSubject required for credits and refunds, ignored for payments
     * @return the entry command
     */
    public record EntryRequest(long amountMinor, String reference, String reason,
                               String approverSubject) {
    }

    /**
     * Void an invoice with a reason and an independent approver.
     *
     * @param reason          required recorded reason
     * @param approverSubject approving subject, distinct from the actor
     * @return the void command
     */
    public record VoidRequest(String reason, String approverSubject) {
    }
}
