import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { send } from '../../api/transport';
import type { InvoiceState } from './domain';
import type { InvoicePage, InvoiceView, Reconciliation } from './domain';

/** The only finance path published in the generated contract (ARC-03). */
const INVOICES_PATH = '/api/v1/finance/invoices';
const RECONCILIATION_PATH = '/api/v1/finance/reconciliation';

/**
 * Build a descriptor on a finance sub-path that is not yet in the generated
 * client. Only the URL is substituted: tenant, correlation, version and
 * idempotency headers stay owned by the shared request builder.
 */
function financeDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, INVOICES_PATH, meta, body);
  return { ...base, url: path };
}

/** Encode an opaque invoice id so it can never alter the command path. */
function invoicePath(id: string, suffix = ''): string {
  return `${INVOICES_PATH}/${encodeURIComponent(id)}${suffix}`;
}

/** Optional query supported by `GET /api/v1/finance/invoices`. */
export type InvoiceListQuery = {
  cursor?: string | undefined;
  limit?: number | undefined;
  status?: InvoiceState | undefined;
  patientId?: string | undefined;
};

/** Body of `POST /api/v1/finance/invoices`. */
export type CreateInvoiceBody = {
  patientId: string;
  currency: string;
  serviceReference: string | null;
  items: Array<{ description: string; quantity: number; unitAmountMinor: number }>;
};

/** Body of a payment, refund or credit command. */
export type EntryBody = {
  amountMinor: number;
  reference: string;
  reason: string | null;
  approverSubject: string | null;
};

/** Body of a void command; the approver must be independent of the actor. */
export type VoidBody = { reason: string; approverSubject: string };

/** The three append-only ledger entry routes. */
export type EntryKind = 'payments' | 'refunds' | 'credits';

/**
 * Fetch one cursor-paginated page of invoices in the active organization.
 *
 * @throws TransportError for every non-ok, offline or timed-out response.
 */
export async function listInvoices(
  query: InvoiceListQuery,
  organizationId: string,
): Promise<InvoicePage> {
  const base = buildRequestDescriptor('GET', INVOICES_PATH, {
    activeOrganization: organizationId,
  });
  const params = new URLSearchParams();
  if (query.cursor !== undefined) {
    params.set('cursor', query.cursor);
  }
  if (query.limit !== undefined) {
    params.set('limit', String(query.limit));
  }
  if (query.status !== undefined) {
    params.set('status', query.status);
  }
  if (query.patientId !== undefined) {
    params.set('patientId', query.patientId);
  }
  const suffix = params.toString();
  return send<InvoicePage>({ ...base, url: suffix === '' ? base.url : `${base.url}?${suffix}` });
}

/** Read one invoice with its lines and ledger trail. */
export async function getInvoice(id: string, organizationId: string): Promise<InvoiceView> {
  return send<InvoiceView>(
    financeDescriptor('GET', invoicePath(id), { activeOrganization: organizationId }),
  );
}

/** Create an itemized draft invoice with a fresh idempotency key. */
export async function createInvoice(
  body: CreateInvoiceBody,
  organizationId: string,
): Promise<InvoiceView> {
  return send<InvoiceView>(
    financeDescriptor(
      'POST',
      INVOICES_PATH,
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
      body,
    ),
  );
}

/** Issue a draft invoice, guarded by its version. */
export async function issueInvoice(
  id: string,
  version: number,
  organizationId: string,
): Promise<InvoiceView> {
  return send<InvoiceView>(
    financeDescriptor('POST', invoicePath(id, '/issue'), {
      activeOrganization: organizationId,
      ifMatch: version,
    }),
  );
}

/**
 * Post a payment, refund or credit. The caller owns the idempotency key so a
 * retry of the same user action reuses it instead of minting a new one.
 */
export async function postEntry(
  id: string,
  kind: EntryKind,
  version: number,
  body: EntryBody,
  organizationId: string,
  idempotencyKey: string,
): Promise<InvoiceView> {
  return send<InvoiceView>(
    financeDescriptor(
      'POST',
      invoicePath(id, `/${kind}`),
      {
        activeOrganization: organizationId,
        ifMatch: version,
        idempotencyKey,
      },
      body,
    ),
  );
}

/** Void an unposted invoice with a reason and an independent approver. */
export async function voidInvoice(
  id: string,
  version: number,
  body: VoidBody,
  organizationId: string,
): Promise<InvoiceView> {
  return send<InvoiceView>(
    financeDescriptor(
      'POST',
      invoicePath(id, '/void'),
      {
        activeOrganization: organizationId,
        ifMatch: version,
        idempotencyKey: crypto.randomUUID(),
      },
      body,
    ),
  );
}

/** Read the finance-only daily reconciliation report. */
export async function getReconciliation(organizationId: string): Promise<Reconciliation> {
  return send<Reconciliation>(
    financeDescriptor('GET', RECONCILIATION_PATH, { activeOrganization: organizationId }),
  );
}
