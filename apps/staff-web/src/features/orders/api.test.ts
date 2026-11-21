import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  acceptOrder,
  asOrdersTransportError,
  clarifyOrder,
  getOrder,
  getOrderHistory,
  listOrders,
  rejectOrder,
  toOrderDetail,
  toOrderRow,
  toTaskEvent,
} from './api';
import type { ExchangeEventWire, OrderWire } from './api';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const organizationId = '11111111-1111-1111-1111-111111111111';

/** A clearly synthetic order wire object with every required field. */
function orderWire(overrides: Partial<OrderWire> = {}): OrderWire {
  return {
    id: 'order-001',
    version: 3,
    patientId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    encounterRef: 'enc-1',
    orderCode: 'ORD-1',
    priority: 'urgent',
    clinicalQuestion: 'Synthetic question',
    destinationOrg: '22222222-2222-2222-2222-222222222222',
    sourceActor: 'synthetic.source',
    responsibleClinician: 'synthetic.clinician',
    workQueue: 'queue-1',
    authorizationId: 'auth-1',
    snapshotId: 'snapshot-1',
    snapshotFingerprint: 'fingerprint-1',
    disclosureId: 'disclosure-1',
    workItemId: 'work-1',
    createdAt: '2026-11-21T09:00:00Z',
    ...overrides,
  };
}

function eventWire(overrides: Partial<ExchangeEventWire> = {}): ExchangeEventWire {
  return {
    id: 'event-1',
    orderVersion: 3,
    action: 'accept',
    fromState: 'requested',
    toState: 'accepted',
    reason: null,
    actorId: 'synthetic.clinician',
    occurredAt: '2026-11-21T10:00:00Z',
    ...overrides,
  };
}

function callAt(index: number): RequestDescriptor {
  return sendMock.mock.calls[index]![0] as RequestDescriptor;
}

beforeEach(() => {
  sendMock.mockReset();
  sendMock.mockResolvedValue({ items: [], nextCursor: null });
});

describe('order reads', () => {
  it('builds the list query from only the supplied filters', async () => {
    await listOrders(
      { status: 'requested', cursor: 'cursor-2', limit: 25 },
      organizationId,
    );
    const descriptor = callAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/orders?status=requested&cursor=cursor-2&limit=25');
    expect(descriptor.headers['X-Active-Organization']).toBe(organizationId);
  });

  it('sends a bare list path when no filter is supplied', async () => {
    await listOrders({}, organizationId);
    expect(callAt(0).url).toBe('/api/v1/orders');
  });

  it('supports a cursor without any other filter', async () => {
    await listOrders({ cursor: 'cursor-9' }, organizationId);
    expect(callAt(0).url).toBe('/api/v1/orders?cursor=cursor-9');
  });

  it('reads one order and its XCH-03 history through the contract paths', async () => {
    await getOrder('order-001', organizationId);
    await getOrderHistory('order-001', organizationId);
    expect(callAt(0).url).toBe('/api/v1/orders/order-001');
    expect(callAt(0).method).toBe('GET');
    expect(callAt(1).url).toBe('/api/v1/xch03/orders/order-001/history');
    expect(callAt(1).headers['X-Active-Organization']).toBe(organizationId);
  });
});

describe('order commands', () => {
  it('accepts with a version precondition and a caller-supplied idempotency key', async () => {
    await acceptOrder('order-001', 3, organizationId, 'key-1', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
    const descriptor = callAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/xch03/orders/order-001/accept');
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.headers['Idempotency-Key']).toBe('key-1');
    expect(descriptor.body).toMatchObject({ localPatientId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' });
  });

  it('rejects with the recorded reason, precondition and idempotency key', async () => {
    await rejectOrder('order-001', 3, organizationId, 'key-2', 'specimen missing');
    const descriptor = callAt(0);
    expect(descriptor.url).toBe('/api/v1/xch03/orders/order-001/reject');
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.headers['Idempotency-Key']).toBe('key-2');
    expect(descriptor.body).toEqual({ reason: 'specimen missing' });
  });

  it('requests clarification with the recorded question', async () => {
    await clarifyOrder('order-001', 4, organizationId, 'key-3', 'which specimen?');
    const descriptor = callAt(0);
    expect(descriptor.url).toBe('/api/v1/xch03/orders/order-001/clarify');
    expect(descriptor.headers['If-Match']).toBe('4');
    expect(descriptor.headers['Idempotency-Key']).toBe('key-3');
    expect(descriptor.body).toEqual({ question: 'which specimen?' });
  });
});

describe('order projections and error normalisation', () => {
  it('maps a wire order into a list row and a detail without dropping identifiers', () => {
    const wire = orderWire();
    const row = toOrderRow(wire);
    const detail = toOrderDetail(wire, 'requested', organizationId);
    expect(row).toMatchObject({
      id: 'order-001',
      orderCode: 'ORD-1',
      patientId: wire.patientId,
      priority: 'urgent',
      version: 3,
    });
    expect(detail).toMatchObject({
      id: 'order-001',
      state: 'requested',
      organizationId,
      version: 3,
    });
  });

  it('maps a history event into a timeline entry', () => {
    expect(toTaskEvent(eventWire({ reason: 'not enough detail' }))).toEqual({
      id: 'event-1',
      orderVersion: 3,
      action: 'accept',
      fromState: 'requested',
      toState: 'accepted',
      reason: 'not enough detail',
      actorId: 'synthetic.clinician',
      occurredAt: '2026-11-21T10:00:00Z',
    });
  });

  it('keeps a typed transport failure and normalises an unexpected rejection', () => {
    const typed = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'STALE',
      retryAfterSeconds: undefined,
    });
    expect(asOrdersTransportError(typed)).toBe(typed);
    const normalised = asOrdersTransportError(new Error('unexpected'));
    expect(normalised.kind).toBe('server');
    expect(normalised.correlationId).toBeUndefined();
  });
});
