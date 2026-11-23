import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  approveConsolidation,
  asDuplicatesTransportError,
  getReviewDetail,
  listDuplicateReviews,
  openReview,
} from './api';
import { alphaOrganizationId, reviewOpen, survivorPatient, duplicatePatient } from './fixtures';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return must(sendMock.mock.calls[call])[0];
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('duplicate review API descriptors', () => {
  it('lists pairs with a tenant header and no write headers', async () => {
    sendMock.mockResolvedValueOnce([]);
    await listDuplicateReviews(alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/duplicate-reviews');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['If-Match']).toBeUndefined();
    expect(descriptor.headers['Idempotency-Key']).toBeUndefined();
  });

  it('encodes both opaque ids in the review detail path', async () => {
    sendMock.mockResolvedValueOnce({});
    await getReviewDetail(survivorPatient.id, duplicatePatient.id, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe(
      `/api/v1/duplicate-reviews/${survivorPatient.id}/${duplicatePatient.id}`,
    );
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('opens a ticket with a fresh idempotency key and no version precondition', async () => {
    sendMock.mockResolvedValueOnce(reviewOpen);
    await openReview(
      { survivorId: survivorPatient.id, duplicateId: duplicatePatient.id },
      alphaOrganizationId,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/duplicate-reviews');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.headers['If-Match']).toBeUndefined();
    expect(descriptor.body).toEqual({
      survivorId: survivorPatient.id,
      duplicateId: duplicatePatient.id,
    });
  });

  it('approves with the duplicate version as If-Match and the confirmation body', async () => {
    sendMock.mockResolvedValueOnce({});
    await approveConsolidation(
      reviewOpen.id,
      duplicatePatient.version,
      {
        reason: 'duplicate registration',
        confirmedIdentifiers: ['MRN-1001@SYNTHETIC', 'MRN-1002@SYNTHETIC'],
        alternateProcess: '',
      },
      alphaOrganizationId,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe(`/api/v1/duplicate-reviews/${reviewOpen.id}/consolidate`);
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['If-Match']).toBe(String(duplicatePatient.version));
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.body).toEqual({
      reason: 'duplicate registration',
      confirmedIdentifiers: ['MRN-1001@SYNTHETIC', 'MRN-1002@SYNTHETIC'],
      alternateProcess: '',
    });
  });
});

describe('asDuplicatesTransportError', () => {
  it('passes an existing typed error through unchanged', () => {
    const error = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'SECOND_REVIEWER_REQUIRED',
      retryAfterSeconds: undefined,
    });
    expect(asDuplicatesTransportError(error)).toBe(error);
  });

  it('normalizes an unexpected rejection to a server error', () => {
    const error = asDuplicatesTransportError(new Error('unexpected'));
    expect(error.kind).toBe('server');
    expect(error.code).toBeUndefined();
    expect(error.correlationId).toBeUndefined();
  });
});
