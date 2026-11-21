import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  addAddendum,
  asRadiologyTransportError,
  getImagingOrder,
  linkStudy,
  listImagingOrders,
  scheduleImagingOrder,
  signInterpretationReport,
  toImagingOrder,
} from './api';
import type { ApiOrderState, ImagingOrderResponse } from './api';
import { alphaOrganizationId, matchingStudy } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function wireResponse(overrides: Partial<ImagingOrderResponse> = {}): ImagingOrderResponse {
  return {
    id: 'imaging-order-a1',
    version: 2,
    patientId: 'patient-a1',
    patientDisplayName: 'Layla Haddad',
    patientLocalName: 'ليلى حداد',
    urgency: 'urgent',
    clinicalQuestion: 'Synthetic question',
    state: 'scheduled',
    accessionNo: 'ACC-2026-0001',
    modality: 'CT',
    scheduledAt: '2026-03-08T08:00:00Z',
    studyInstanceUid: null,
    interpretation: null,
    ...overrides,
  };
}

describe('imaging order API client', () => {
  it('lists orders with every optional query parameter encoded on the tenant path', async () => {
    const page = { items: [], nextCursor: null };
    sendMock.mockResolvedValueOnce(page);

    await expect(
      listImagingOrders({ status: 'scheduled', cursor: 'next page', limit: 25 }, alphaOrganizationId),
    ).resolves.toEqual(page);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe(
      '/api/v1/imaging/orders?status=scheduled&cursor=next+page&limit=25',
    );
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.body).toBeUndefined();
  });

  it('lists orders without a query string when no filter is supplied', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });
    await listImagingOrders({}, alphaOrganizationId);
    expect(descriptorAt(0).url).toBe('/api/v1/imaging/orders');
  });

  it('percent-encodes an order identifier when reading one order', async () => {
    const response = wireResponse();
    sendMock.mockResolvedValueOnce(response);

    await expect(getImagingOrder('order/1', alphaOrganizationId)).resolves.toEqual(response);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/imaging/orders/order%2F1');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('schedules an order with a version precondition and a fresh idempotency key', async () => {
    const response = wireResponse();
    sendMock.mockResolvedValueOnce(response);

    await expect(
      scheduleImagingOrder(
        'order-1',
        2,
        { accessionNo: 'ACC-1', modality: 'MRI', scheduledAt: '2026-03-10T08:00:00Z' },
        alphaOrganizationId,
      ),
    ).resolves.toEqual(response);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/imaging/orders/order-1/schedule');
    expect(descriptor.headers['If-Match']).toBe('2');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.body).toEqual({
      accessionNo: 'ACC-1',
      modality: 'MRI',
      scheduledAt: '2026-03-10T08:00:00Z',
    });
  });

  it('links a verified study on the study sub-path', async () => {
    sendMock.mockResolvedValueOnce(wireResponse());

    await linkStudy('order/1', 3, matchingStudy, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/imaging/orders/order%2F1/study');
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.body).toEqual({
      studyInstanceUid: matchingStudy.studyInstanceUid,
      patientId: matchingStudy.patientId,
      accessionNo: matchingStudy.accessionNo,
    });
  });

  it('signs an interpretation with all free-text sections', async () => {
    sendMock.mockResolvedValueOnce(wireResponse());
    const input = {
      technique: 'CT',
      comparison: 'Prior',
      findings: 'Clear',
      impression: 'Normal',
      followUp: 'None',
    };

    await signInterpretationReport('order-1', 3, input, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/imaging/orders/order-1/interpretation');
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.body).toEqual(input);
  });

  it('appends a linked addendum with a version precondition', async () => {
    sendMock.mockResolvedValueOnce(wireResponse());

    await addAddendum('order-1', 4, 'Synthetic addendum', alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/imaging/orders/order-1/addenda');
    expect(descriptor.headers['If-Match']).toBe('4');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.body).toEqual({ text: 'Synthetic addendum' });
  });

  it('propagates a typed transport failure unchanged', async () => {
    const failure = new TransportError('conflict', {
      correlationId: 'corr-imaging',
      code: 'CONFLICT',
      retryAfterSeconds: undefined,
    });
    sendMock.mockRejectedValueOnce(failure);
    await expect(getImagingOrder('order-1', alphaOrganizationId)).rejects.toBe(failure);
  });
});

describe('toImagingOrder', () => {
  it('keeps the local name when the API provides one', () => {
    const order = toImagingOrder(wireResponse());
    expect(order.patientName).toEqual({
      latinName: 'Layla Haddad',
      localName: 'ليلى حداد',
    });
  });

  it('omits the local name when the API does not provide one', () => {
    const order = toImagingOrder(wireResponse({ patientLocalName: null }));
    expect(order.patientName).toEqual({ latinName: 'Layla Haddad' });
    expect(order.state).toBe('scheduled');
  });

  it('maps every wire state onto a domain state', () => {
    const states: readonly ApiOrderState[] = [
      'received',
      'scheduled',
      'study_linked',
      'interpreted',
      'addended',
    ];
    const mapped = states.map((state) => toImagingOrder(wireResponse({ state })).state);
    expect(mapped).toEqual(['received', 'scheduled', 'study-linked', 'interpreted', 'addended']);
  });

  it('carries a signed interpretation and its addenda to the domain view', () => {
    const order = toImagingOrder(
      wireResponse({
        state: 'addended',
        interpretation: {
          technique: 'CT',
          comparison: 'Prior',
          findings: 'Findings',
          impression: 'Impression',
          followUp: 'Follow-up',
          signedBy: 'synthetic.radiologist',
          signedAt: '2026-03-08T09:00:00Z',
          addenda: [
            {
              id: 'addendum-1',
              text: 'Addendum text',
              author: 'synthetic.radiologist',
              addedAt: '2026-03-08T10:00:00Z',
            },
          ],
        },
      }),
    );
    expect(order.state).toBe('addended');
    expect(order.interpretation?.addenda).toEqual([
      {
        id: 'addendum-1',
        text: 'Addendum text',
        author: 'synthetic.radiologist',
        addedAt: '2026-03-08T10:00:00Z',
      },
    ]);
  });
});

describe('asRadiologyTransportError', () => {
  it('passes a typed error through unchanged', () => {
    const failure = new TransportError('forbidden', {
      correlationId: 'corr-1',
      code: 'FORBIDDEN',
      retryAfterSeconds: undefined,
    });
    expect(asRadiologyTransportError(failure)).toBe(failure);
  });

  it('normalizes an unexpected rejection to a server error without metadata', () => {
    const error = asRadiologyTransportError(new Error('unexpected'));
    expect(error.kind).toBe('server');
    expect(error.correlationId).toBeUndefined();
    expect(error.code).toBeUndefined();
  });
});
