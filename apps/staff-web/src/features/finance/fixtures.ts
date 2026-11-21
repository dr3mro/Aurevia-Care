import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { Invoice, InvoiceItem, InvoiceView, LedgerEntry } from './domain';

/** The two synthetic tenants; their local invoice ids intentionally collide. */
export const alphaOrganizationId = syntheticOrganizations[0]!.id;
export const betaOrganizationId = syntheticOrganizations[1]!.id;

/** Synthetic itemized lines for one invoice. */
export const syntheticItems: readonly InvoiceItem[] = [
  {
    id: 'item-1',
    invoiceId: 'invoice-001',
    lineNo: 1,
    description: 'Synthetic consultation',
    quantity: 1,
    unitAmountMinor: 3000,
    amountMinor: 3000,
  },
  {
    id: 'item-2',
    invoiceId: 'invoice-001',
    lineNo: 2,
    description: 'Synthetic dressing',
    quantity: 2,
    unitAmountMinor: 1000,
    amountMinor: 2000,
  },
];

/** Synthetic linked, immutable ledger entries for one invoice. */
export const syntheticEntries: readonly LedgerEntry[] = [
  {
    id: 'entry-1',
    invoiceId: 'invoice-001',
    invoiceVersion: 2,
    type: 'payment',
    amountMinor: 2000,
    currency: 'USD',
    reference: 'synthetic-payment-1',
    reason: null,
    approverSubject: null,
    receiptNo: 1,
    actorId: 'synthetic.finance',
    occurredAt: '2026-03-09T10:00:00Z',
  },
  {
    id: 'entry-2',
    invoiceId: 'invoice-001',
    invoiceVersion: 3,
    type: 'refund',
    amountMinor: 500,
    currency: 'USD',
    reference: 'synthetic-refund-1',
    reason: 'Synthetic overpayment',
    approverSubject: 'synthetic.approver',
    receiptNo: 2,
    actorId: 'synthetic.finance',
    occurredAt: '2026-03-10T10:00:00Z',
  },
  {
    id: 'entry-3',
    invoiceId: 'invoice-001',
    invoiceVersion: 4,
    type: 'credit',
    amountMinor: 300,
    currency: 'USD',
    reference: 'synthetic-credit-1',
    reason: 'Synthetic goodwill adjustment',
    approverSubject: 'synthetic.approver',
    receiptNo: 3,
    actorId: 'synthetic.finance',
    occurredAt: '2026-03-11T10:00:00Z',
  },
];

/**
 * Build one synthetic invoice. Every field is clearly synthetic; no patient
 * name, real identifier or free clinical text is present.
 */
export function syntheticInvoice(overrides: Partial<Invoice> = {}): Invoice {
  return {
    id: 'invoice-001',
    version: 1,
    patientId: 'patient-001',
    currency: 'USD',
    state: 'issued',
    totalMinor: 5000,
    paidMinor: 0,
    refundedMinor: 0,
    creditedMinor: 0,
    balanceMinor: 5000,
    serviceReference: 'Synthetic consultation',
    createdAt: '2026-03-09T09:00:00Z',
    issuedAt: '2026-03-09T09:05:00Z',
    voidReason: null,
    voidedBySubject: null,
    ...overrides,
  };
}

/** Build one synthetic invoice view with lines and a ledger trail. */
export function syntheticInvoiceView(overrides: Partial<Invoice> = {}): InvoiceView {
  return {
    invoice: syntheticInvoice(overrides),
    items: syntheticItems,
    entries: syntheticEntries,
  };
}
