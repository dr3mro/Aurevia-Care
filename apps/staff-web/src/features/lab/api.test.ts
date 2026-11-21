import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  accession,
  collect,
  getAccession,
  listAccessions,
  recollect,
  receive,
  reject,
} from './api';
import { alphaOrganizationId } from './fixtures';
import type { AccessionBody } from './types';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

const accessionBody: AccessionBody = {
  accessionNo: 'SYN-ACC-0009',
  accessionIssuer: 'SYNLAB',
  patientId: 'bbbbbbbb-0000-4000-8000-000000000009',
  sourceOrderRef: 'SYN-ORDER-0009',
};

describe('lab accession API client', () => {
  it('lists accessions on the contract path with only the tenant header', async () => {
    const page = { items: [], nextCursor: null };
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
      { cursor: 'cursor-2', state: 'rejected', limit: 25 },
      alphaOrganizationId,
    );

    const url = descriptorAt(0).url;
    expect(url).toContain('cursor=cursor-2');
    expect(url).toContain('state=rejected');
    expect(url).toContain('limit=25');
  });

  it('reads one accession by its opaque id', async () => {
    sendMock.mockResolvedValueOnce({ accession: {}, events: [] });

    await getAccession('acc-1', alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/lab/accessions/acc-1');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('accessions a requisition with a fresh idempotency key and full body', async () => {
    sendMock.mockResolvedValueOnce({ id: 'acc-9' });

    await expect(accession(accessionBody, alphaOrganizationId)).resolves.toEqual({ id: 'acc-9' });

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/lab/accessions');
    expect(descriptor.body).toEqual(accessionBody);
    expect(descriptor.headers['Idempotency-Key']).toBeTruthy();
  });

  it('sends collect with the version precondition and matching identity', async () => {
    const body = {
      patientId: accessionBody.patientId,
      accessionNo: accessionBody.accessionNo,
      accessionIssuer: accessionBody.accessionIssuer,
      specimenType: 'SYN-WHOLE-BLOOD',
      siteCode: 'SYN-MAIN',
      collector: 'SYN-COLLECTOR',
      collectedAt: '2026-03-09T06:00:00Z',
      expectedVersion: 3,
    };
    sendMock.mockResolvedValueOnce({ id: 'acc-1' });

    await collect('acc-1', body, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/lab/accessions/acc-1/collect');
    expect(descriptor.body).toEqual(body);
    expect(descriptor.headers['If-Match']).toBe('3');
  });

  it('sends receive with the version precondition', async () => {
    const body = {
      patientId: accessionBody.patientId,
      accessionNo: accessionBody.accessionNo,
      accessionIssuer: accessionBody.accessionIssuer,
      quality: 'SYN-ACCEPTABLE',
      receivedAt: '2026-03-09T07:00:00Z',
      expectedVersion: 4,
    };
    sendMock.mockResolvedValueOnce({ id: 'acc-1' });

    await receive('acc-1', body, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/lab/accessions/acc-1/receive');
    expect(descriptor.body).toEqual(body);
    expect(descriptor.headers['If-Match']).toBe('4');
  });

  it('sends reject with the retained reason and notification', async () => {
    const body = { reason: 'SYN hemolysis', notification: 'SYN-NOTIFY', expectedVersion: 5 };
    sendMock.mockResolvedValueOnce({ id: 'acc-1' });

    await reject('acc-1', body, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/lab/accessions/acc-1/reject');
    expect(descriptor.body).toEqual(body);
    expect(descriptor.headers['If-Match']).toBe('5');
  });

  it('sends recollect guarded by the rejected specimen version', async () => {
    const body = { accessionNo: 'SYN-ACC-0002', accessionIssuer: 'SYNLAB', expectedVersion: 6 };
    sendMock.mockResolvedValueOnce({ id: 'acc-2' });

    await recollect('acc-1', body, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/lab/accessions/acc-1/recollect');
    expect(descriptor.body).toEqual(body);
    expect(descriptor.headers['If-Match']).toBe('6');
  });

  it('propagates a typed transport failure unchanged', async () => {
    const failure = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'STALE_VERSION',
      retryAfterSeconds: undefined,
    });
    sendMock.mockRejectedValueOnce(failure);

    await expect(listAccessions({}, alphaOrganizationId)).rejects.toBe(failure);
  });
});
