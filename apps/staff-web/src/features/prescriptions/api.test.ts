import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { getPrescription, reconcileTransmission, searchDrugCatalog, submitPrescription } from './api';
import { alphaOrganizationId, createSyntheticDraft, syntheticCatalog } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

describe('searchDrugCatalog', () => {
  it('searches the approved drugs family with both filters and the tenant header', async () => {
    sendMock.mockResolvedValueOnce(syntheticCatalog);

    await expect(searchDrugCatalog('amox', 'tablet', alphaOrganizationId)).resolves.toBe(
      syntheticCatalog,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/catalogs/drugs?q=amox&formulation=tablet');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Idempotency-Key']).toBeUndefined();
  });

  it('sends only the query when no formulation is supplied', async () => {
    sendMock.mockResolvedValueOnce([]);
    await searchDrugCatalog('amox', '', alphaOrganizationId);
    expect(descriptorAt(0).url).toBe('/api/v1/catalogs/drugs?q=amox');
  });

  it('sends only the formulation when no query is supplied', async () => {
    sendMock.mockResolvedValueOnce([]);
    await searchDrugCatalog('', 'tablet', alphaOrganizationId);
    expect(descriptorAt(0).url).toBe('/api/v1/catalogs/drugs?formulation=tablet');
  });

  it('omits the query string when both filters are empty', async () => {
    sendMock.mockResolvedValueOnce([]);
    await searchDrugCatalog('', '', alphaOrganizationId);
    expect(descriptorAt(0).url).toBe('/api/v1/catalogs/drugs');
  });

  it('propagates a typed transport failure unchanged', async () => {
    const failure = new TransportError('forbidden', {
      correlationId: 'corr-1',
      code: 'DENIED',
      retryAfterSeconds: undefined,
    });
    sendMock.mockRejectedValueOnce(failure);
    await expect(searchDrugCatalog('amox', '', alphaOrganizationId)).rejects.toBe(failure);
  });
});

describe('submitPrescription', () => {
  it('posts the complete draft with a fresh idempotency key and no version precondition', async () => {
    const response = {
      id: 'rx-synthetic-001',
      version: 2,
      submissionId: 'submission-synthetic-001',
      transmission: 'confirmed' as const,
      events: [],
    };
    sendMock.mockResolvedValueOnce(response);
    const draft = createSyntheticDraft();

    await expect(submitPrescription(draft, alphaOrganizationId)).resolves.toBe(response);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/prescriptions');
    expect(descriptor.body).toEqual(draft);
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.headers['If-Match']).toBeUndefined();
  });
});

describe('submitPrescription update', () => {
  it('posts a version precondition to the existing prescription path', async () => {
    sendMock.mockResolvedValueOnce({
      id: 'rx-synthetic-001',
      version: 3,
      submissionId: 'submission-synthetic-001',
      transmission: 'ambiguous',
      events: [],
    });

    await submitPrescription(createSyntheticDraft(), alphaOrganizationId, {
      id: 'rx/synthetic:001',
      version: 2,
    });

    const descriptor = descriptorAt(0);
    expect(descriptor.url).toBe('/api/v1/prescriptions/rx%2Fsynthetic%3A001/submit');
    expect(descriptor.headers['If-Match']).toBe('2');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('getPrescription', () => {
  it('reads the authoritative record with the tenant header and no precondition', async () => {
    const response = {
      id: 'rx/synthetic:001',
      version: 4,
      submissionId: 'submission-synthetic-001',
      transmission: 'confirmed' as const,
      events: [],
    };
    sendMock.mockResolvedValueOnce(response);

    await expect(getPrescription('rx/synthetic:001', alphaOrganizationId)).resolves.toBe(response);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/prescriptions/rx%2Fsynthetic%3A001');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['If-Match']).toBeUndefined();
  });
});

describe('reconcileTransmission', () => {
  it('posts a reconciliation command to the encoded prescription path', async () => {
    const response = {
      id: 'rx/synthetic:001',
      version: 3,
      submissionId: 'submission-synthetic-001',
      transmission: 'confirmed' as const,
      events: [],
    };
    sendMock.mockResolvedValueOnce(response);

    await expect(reconcileTransmission('rx/synthetic:001', alphaOrganizationId)).resolves.toBe(
      response,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/prescriptions/rx%2Fsynthetic%3A001/reconcile');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptor.body).toBeUndefined();
  });
});
