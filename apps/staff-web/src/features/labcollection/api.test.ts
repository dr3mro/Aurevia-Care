import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  collectSpecimen,
  listAccessions,
  readLabel,
  rejectSpecimen,
  toCollectionOrder,
} from './api';
import type { AccessionViewResponse } from './api';
import { alphaOrganizationId, labelAlpha, specimenDetailsAlpha } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

/** Build one synthetic API accession view with every contract field populated. */
function apiView(
  overrides: Partial<AccessionViewResponse['accession']> = {},
): AccessionViewResponse {
  return {
    accession: {
      id: 'accession-100',
      version: 1,
      accessionNo: 'ACC-100',
      accessionIssuer: 'SYN-ISSUER',
      patientId: 'patient-100',
      sourceOrderRef: 'ORDER-100',
      state: 'accessioned',
      specimenType: null,
      siteCode: null,
      collector: null,
      collectedAt: null,
      receivedAt: null,
      quality: null,
      rejectionReason: null,
      rejectionNotification: null,
      replacesAccessionId: null,
      ...overrides,
    },
    events: [],
  };
}

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

describe('lab accession API client', () => {
  it('lists accessions on the contract path with only the tenant header', async () => {
    const page = { items: [apiView()], nextCursor: null };
    sendMock.mockResolvedValueOnce(page);

    await expect(listAccessions({}, alphaOrganizationId)).resolves.toEqual(page);

    expect(sendMock).toHaveBeenCalledTimes(1);
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/lab/accessions');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('includes optional cursor, state and limit filters', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });

    await listAccessions(
      { cursor: 'cursor-2', state: 'collected', limit: 25 },
      alphaOrganizationId,
    );

    const url = descriptorAt(0).url;
    expect(url).toContain('cursor=cursor-2');
    expect(url).toContain('state=collected');
    expect(url).toContain('limit=25');
  });

  it('propagates a typed transport failure unchanged', async () => {
    const failure = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'STALE',
      retryAfterSeconds: undefined,
    });
    sendMock.mockRejectedValueOnce(failure);

    await expect(listAccessions({}, alphaOrganizationId)).rejects.toBe(failure);
  });

  it('records a collection with a fresh idempotency key and the full body', async () => {
    sendMock.mockResolvedValueOnce(apiView());

    await collectSpecimen(
      'accession-100',
      {
        ...specimenDetailsAlpha,
        patientId: 'patient-100',
        accessionNo: 'ACC-100',
        accessionIssuer: 'SYN-ISSUER',
        expectedVersion: 1,
      },
      alphaOrganizationId,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/lab/specimens/accession-100/collect');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.headers['If-Match']).toBe('1');
    expect(descriptor.body).toMatchObject({ specimenType: 'SERUM', expectedVersion: 1 });
  });

  it('reads label data for a collected specimen', async () => {
    sendMock.mockResolvedValueOnce(labelAlpha);

    await expect(readLabel('accession-100', alphaOrganizationId)).resolves.toEqual(labelAlpha);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/lab/specimens/accession-100/label');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('rejects a specimen with a reason, detail and version precondition', async () => {
    sendMock.mockResolvedValueOnce({});

    await rejectSpecimen(
      'accession-100',
      {
        reason: 'other',
        detail: 'synthetic reason',
        orderingClinician: 'synthetic.clinician',
        notifyPatient: false,
        expectedVersion: 1,
      },
      alphaOrganizationId,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/lab/specimens/accession-100/reject');
    expect(descriptor.headers['If-Match']).toBe('1');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.body).toMatchObject({ reason: 'other', notifyPatient: false });
  });
});

describe('toCollectionOrder', () => {
  it('maps an API view into the tenant-scoped collection order', () => {
    expect(toCollectionOrder(apiView(), alphaOrganizationId)).toEqual({
      organizationId: alphaOrganizationId,
      id: 'accession-100',
      accessionNo: 'ACC-100',
      accessionIssuer: 'SYN-ISSUER',
      patientId: 'patient-100',
      patientName: { latinName: 'patient-100' },
      patientMrn: 'patient-100',
      patientMrnIssuer: 'SYN-ISSUER',
      sourceOrderRef: 'ORDER-100',
      state: 'accessioned',
      version: 1,
    });
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
