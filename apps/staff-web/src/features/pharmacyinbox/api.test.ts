import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  acceptPrescription,
  askClarification,
  asPharmacyTransportError,
  getPharmacyStatus,
  getPrescription,
  listPrescriptions,
  recordDispensing,
  rejectPrescription,
} from './api';
import { syntheticDispensingEvidence } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('pharmacy inbox and status API', () => {
  it('lists all routed prescriptions without a status or cursor filter', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    await listPrescriptions('all', undefined, 'org-alpha');

    expect(descriptorAt(0).method).toBe('GET');
    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/inbox?');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe('org-alpha');
  });

  it('sends a concrete status and cursor when listing', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    await listPrescriptions('accepted', 'cursor-1', 'org-alpha');

    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/inbox?status=accepted&cursor=cursor-1');
  });

  it('reads one routed prescription', async () => {
    sendMock.mockResolvedValueOnce({ id: 'rx-1' });
    await getPrescription('rx 1', 'org-alpha');

    expect(descriptorAt(0).method).toBe('GET');
    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/inbox/rx%201');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe('org-alpha');
  });

  it('reads the current status view', async () => {
    sendMock.mockResolvedValueOnce({ prescriptionId: 'rx-1' });
    await getPharmacyStatus('rx-1', 'org-alpha');

    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/status/rx-1');
  });

  it('accepts with a version precondition and a fresh idempotency key', async () => {
    sendMock.mockResolvedValueOnce({ id: 'event-1' });
    await acceptPrescription('rx-1', 3, 'org-alpha');

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/pharmacy/status/rx-1/accept');
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.body).toBeUndefined();
  });

  it('rejects with a mandatory reason body', async () => {
    sendMock.mockResolvedValueOnce({ id: 'event-2' });
    await rejectPrescription('rx-1', 3, 'out of stock', 'org-alpha');

    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/status/rx-1/reject');
    expect(descriptorAt(0).body).toEqual({ reason: 'out of stock' });
    expect(descriptorAt(0).headers['If-Match']).toBe('3');
  });

  it('asks for clarification with a question body', async () => {
    sendMock.mockResolvedValueOnce({ id: 'event-3' });
    await askClarification('rx-1', 3, 'Which strength?', 'org-alpha');

    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/status/rx-1/clarification');
    expect(descriptorAt(0).body).toEqual({ question: 'Which strength?' });
  });

  it('records dispensing evidence with its own body', async () => {
    sendMock.mockResolvedValueOnce({ id: 'event-4' });
    await recordDispensing('rx-1', 3, syntheticDispensingEvidence, 'org-alpha');

    expect(descriptorAt(0).url).toBe('/api/v1/pharmacy/status/rx-1/dispensing-evidence');
    expect(descriptorAt(0).body).toEqual({
      dispensedBy: 'synthetic.pharmacist',
      dispensedAt: '2026-11-21T10:30:00Z',
      handedOver: '30 tablets',
    });
    expect(descriptorAt(0).headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('passes a typed transport error through and normalizes an unexpected rejection', () => {
    const typed = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'STALE_VERSION',
      retryAfterSeconds: undefined,
    });
    expect(asPharmacyTransportError(typed)).toBe(typed);

    const normalized = asPharmacyTransportError(new Error('boom'));
    expect(normalized.kind).toBe('server');
    expect(normalized.correlationId).toBeUndefined();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
