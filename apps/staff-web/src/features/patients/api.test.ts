import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { asPatientsTransportError, getPatient, registerPatient, searchPatients } from './api';
import { EMPTY_REGISTRATION } from './domain';
import { alphaOrganizationId } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

describe('patient API client', () => {
  it('searches on the versioned path with the encoded name and only the tenant header', async () => {
    const rows = [{ id: 'patient-1' }];
    sendMock.mockResolvedValueOnce(rows);

    await expect(searchPatients('Layla Haddad', alphaOrganizationId)).resolves.toEqual(rows);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/patients?name=Layla%20Haddad');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Idempotency-Key']).toBeUndefined();
    expect(descriptor.body).toBeUndefined();
  });

  it('percent-encodes an Arabic search name', async () => {
    sendMock.mockResolvedValueOnce([]);

    await searchPatients('ليلى', alphaOrganizationId);

    expect(descriptorAt(0).url).toBe('/api/v1/patients?name=%D9%84%D9%8A%D9%84%D9%89');
  });

  it('registers with a fresh idempotency key and no tenant field in the body', async () => {
    const created = { id: 'patient-2', displayName: 'Synthetic New Patient' };
    sendMock.mockResolvedValueOnce(created);

    const draft = {
      ...EMPTY_REGISTRATION,
      displayName: 'Synthetic New Patient',
      mrn: 'MRN-2001',
      issuer: 'SYNTHETIC',
      dob: '1992-08-03',
      language: 'en',
    };
    await expect(registerPatient(draft, alphaOrganizationId)).resolves.toEqual(created);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/patients');
    expect(descriptor.body).toEqual({
      displayName: 'Synthetic New Patient',
      mrn: 'MRN-2001',
      issuer: 'SYNTHETIC',
      dob: '1992-08-03',
      dobUnknownReason: null,
      language: 'en',
      contacts: '{}',
      identifiers: '[]',
      consents: '[]',
    });
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('reads one patient with its duplicate warnings on the encoded path', async () => {
    const viewResponse = { patient: { id: 'patient-1' }, duplicates: [] };
    sendMock.mockResolvedValueOnce(viewResponse);

    await expect(getPatient('patient/1', alphaOrganizationId)).resolves.toEqual(viewResponse);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/patients/patient%2F1');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('propagates a typed transport failure unchanged', async () => {
    const failure = new TransportError('forbidden', {
      correlationId: 'corr-patients',
      code: 'FORBIDDEN',
      retryAfterSeconds: undefined,
    });
    sendMock.mockRejectedValueOnce(failure);

    await expect(searchPatients('Layla', alphaOrganizationId)).rejects.toBe(failure);
  });
});

describe('asPatientsTransportError', () => {
  it('passes a typed error through unchanged', () => {
    const failure = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'CONFLICT',
      retryAfterSeconds: undefined,
    });
    expect(asPatientsTransportError(failure)).toBe(failure);
  });

  it('normalizes an unexpected rejection to a server error with no correlation id', () => {
    const error = asPatientsTransportError(new Error('unexpected'));
    expect(error.kind).toBe('server');
    expect(error.correlationId).toBeUndefined();
    expect(error.code).toBeUndefined();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
