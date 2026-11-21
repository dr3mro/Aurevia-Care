/**
 * Finance domain vocabulary for FIN-01 self-pay invoices.
 *
 * Money is always an integer count of one currency's minor units. The types
 * mirror the finance contract fields; no clinical narrative is part of a
 * finance view. The action table mirrors the server lifecycle so the UI never
 * offers a command the API would reject on state grounds.
 */

/** Lifecycle of a self-pay invoice (FIN-01). */
export type InvoiceState = 'draft' | 'issued' | 'partially_paid' | 'paid' | 'void';

/** Append-only ledger entry vocabulary (FIN-01). */
export type LedgerEntryType = 'payment' | 'refund' | 'credit';

/** Current projection of one invoice. */
export type Invoice = {
  id: string;
  version: number;
  patientId: string;
  currency: string;
  state: InvoiceState;
  totalMinor: number;
  paidMinor: number;
  refundedMinor: number;
  creditedMinor: number;
  balanceMinor: number;
  serviceReference: string | null;
  createdAt: string;
  issuedAt: string | null;
  voidReason: string | null;
  voidedBySubject: string | null;
};

/** One immutable itemized line. */
export type InvoiceItem = {
  id: string;
  invoiceId: string;
  lineNo: number;
  description: string;
  quantity: number;
  unitAmountMinor: number;
  amountMinor: number;
};

/** One immutable, numbered finance ledger entry. */
export type LedgerEntry = {
  id: string;
  invoiceId: string;
  invoiceVersion: number;
  type: LedgerEntryType;
  amountMinor: number;
  currency: string;
  reference: string;
  reason: string | null;
  approverSubject: string | null;
  receiptNo: number;
  actorId: string;
  occurredAt: string;
};

/** An invoice together with its lines and full ledger trail. */
export type InvoiceView = {
  invoice: Invoice;
  items: readonly InvoiceItem[];
  entries: readonly LedgerEntry[];
};

/** One cursor-paginated page of invoice views. */
export type InvoicePage = {
  items: readonly InvoiceView[];
  nextCursor: string | null;
};

/** One day/currency row of the finance-only reconciliation report. */
export type DailyTotal = {
  day: string;
  currency: string;
  issuedMinor: number;
  paymentMinor: number;
  refundMinor: number;
  creditMinor: number;
  netMinor: number;
  entryCount: number;
};

/** Daily reconciliation totals; no clinical field is present. */
export type Reconciliation = { days: readonly DailyTotal[] };

/** All invoice states, in screen-filter order. */
export const invoiceStates: readonly InvoiceState[] = [
  'draft',
  'issued',
  'partially_paid',
  'paid',
  'void',
];

/** Commands the UI may offer for one invoice in its current state. */
export type InvoiceActions = {
  issue: boolean;
  payment: boolean;
  refund: boolean;
  credit: boolean;
  void: boolean;
};

const noActions: InvoiceActions = {
  issue: false,
  payment: false,
  refund: false,
  credit: false,
  void: false,
};

/**
 * Derive the commands allowed by the invoice lifecycle. A void invoice is
 * terminal; a draft may be issued or voided; an issued invoice may receive
 * payments, credits or be voided; a partially paid invoice may also be
 * refunded once money has actually been received; a paid invoice may only be
 * reversed by a linked refund.
 *
 * @param invoice invoice whose current state and paid total are inspected
 * @returns one boolean per command, never a clinical or patient decision
 */
export function availableActions(invoice: Invoice): InvoiceActions {
  if (invoice.state === 'void') {
    return noActions;
  }
  if (invoice.state === 'draft') {
    return { issue: true, payment: false, refund: false, credit: false, void: true };
  }
  if (invoice.state === 'issued') {
    return { issue: false, payment: true, refund: false, credit: true, void: true };
  }
  if (invoice.state === 'partially_paid') {
    return {
      issue: false,
      payment: true,
      refund: invoice.paidMinor > 0,
      credit: true,
      void: false,
    };
  }
  return { issue: false, payment: false, refund: true, credit: false, void: false };
}
