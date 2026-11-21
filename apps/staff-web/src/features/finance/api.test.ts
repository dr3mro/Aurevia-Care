import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  createInvoice,
  getInvoice,
  getReconciliation,
  issueInvoice,
  listInvoices,
  postEntry,
  voidInvoice,
} from './api';
import type { EntryKind } from './api';
import { alphaOrganizationId } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

beforeEach(() => {
  sendMock.mockReset();
  vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue('00000000-0000-0000-0000-000000000001');
});

describe('finance API client', () => {
  it('lists invoices on the contract path with only the tenant header', async () => {
    const page = { items: [], nextCursor: null };
    sendMock.mockResolvedValueOnce(page);

    await expect(listInvoices({}, alphaOrganizationId)).resolves.toBe(page);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/finance/invoices');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['If-Match']).toBeUndefined();
  });

  it('encodes every optional list filter into the query string', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });

    await listInvoices(
      {
        cursor: 'cursor-2',
        limit: 25,
        status: 'partially_paid',
        patientId: 'patient-001',
      },
      alphaOrganizationId,
    );

    const url = new URL(descriptorAt(0).url, 'https://synthetic.invalid');
    expect(url.pathname).toBe('/api/v1/finance/invoices');
    expect(url.searchParams.get('cursor')).toBe('cursor-2');
    expect(url.searchParams.get('limit')).toBe('25');
    expect(url.searchParams.get('status')).toBe('partially_paid');
    expect(url.searchParams.get('patientId')).toBe('patient-001');
  });

  it('propagates a typed transport failure unchanged', async () => {
    const failure = new TransportError('forbidden', {
      correlationId: 'corr-1',
      code: 'DENIED',
      retryAfterSeconds: undefined,
    });
    sendMock.mockRejectedValueOnce(failure);

    await expect(listInvoices({}, alphaOrganizationId)).rejects.toBe(failure);
  });

  it('reads one invoice by its encoded opaque id', async () => {
    const view = { invoice: {}, items: [], entries: [] };
    sendMock.mockResolvedValueOnce(view);

    await expect(getInvoice('invoice/001', alphaOrganizationId)).resolves.toBe(view);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/finance/invoices/invoice%2F001');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('creates a draft invoice with a fresh idempotency key', async () => {
    const body = {
      patientId: 'patient-001',
      currency: 'USD',
      serviceReference: null,
      items: [{ description: 'Synthetic line', quantity: 1, unitAmountMinor: 1000 }],
    };
    sendMock.mockResolvedValueOnce({ invoice: {}, items: [], entries: [] });

    await createInvoice(body, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/finance/invoices');
    expect(descriptor.body).toEqual(body);
    expect(descriptor.headers['Idempotency-Key']).toBe('00000000-0000-0000-0000-000000000001');
  });

  it('issues an invoice with a version precondition', async () => {
    sendMock.mockResolvedValueOnce({ invoice: {}, items: [], entries: [] });

    await issueInvoice('invoice-001', 4, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/finance/invoices/invoice-001/issue');
    expect(descriptor.headers['If-Match']).toBe('4');
    expect(descriptor.body).toBeUndefined();
  });

  it.each(['payments', 'refunds', 'credits'] as const)(
    'posts a %s entry with the caller supplied idempotency key',
    async (kind: EntryKind) => {
      const body = {
        amountMinor: 1200,
        reference: 'synthetic-ref',
        reason: 'Synthetic reason',
        approverSubject: 'synthetic.approver',
      };
      sendMock.mockResolvedValueOnce({ invoice: {}, items: [], entries: [] });

      await postEntry('invoice-001', kind, 3, body, alphaOrganizationId, 'stable-key');

      const descriptor = descriptorAt(0);
      expect(descriptor.method).toBe('POST');
      expect(descriptor.url).toBe(`/api/v1/finance/invoices/invoice-001/${kind}`);
      expect(descriptor.headers['If-Match']).toBe('3');
      expect(descriptor.headers['Idempotency-Key']).toBe('stable-key');
      expect(descriptor.body).toEqual(body);
    },
  );

  it('voids an invoice with a reason, approver and version precondition', async () => {
    const body = { reason: 'Synthetic cancellation', approverSubject: 'synthetic.approver' };
    sendMock.mockResolvedValueOnce({ invoice: {}, items: [], entries: [] });

    await voidInvoice('invoice-001', 2, body, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/finance/invoices/invoice-001/void');
    expect(descriptor.headers['If-Match']).toBe('2');
    expect(descriptor.headers['Idempotency-Key']).toBe('00000000-0000-0000-0000-000000000001');
    expect(descriptor.body).toEqual(body);
  });

  it('reads the finance-only reconciliation report', async () => {
    const report = { days: [] };
    sendMock.mockResolvedValueOnce(report);

    await expect(getReconciliation(alphaOrganizationId)).resolves.toBe(report);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/finance/reconciliation');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });
});
