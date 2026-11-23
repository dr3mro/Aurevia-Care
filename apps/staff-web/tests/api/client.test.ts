import { describe, expect, it } from 'vitest';
import { API_PREFIX, buildRequestDescriptor, refuseNetworkCall } from '../../src/api/client';
import type { ApiPath } from '../../src/api/client';

describe('ARC-03 typed request descriptors (no transport)', () => {
  it('uses the versioned paths from the generated contract only', () => {
    const path: ApiPath = '/api/v1/facilities';
    expect(API_PREFIX).toBe('/api/v1');
    const descriptor = buildRequestDescriptor('GET', path, {
      activeOrganization: '11111111-1111-1111-1111-111111111111',
    });
    expect(descriptor.url).toBe(path);
    expect(descriptor.url.startsWith(API_PREFIX)).toBe(true);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.headers).toEqual({
      'X-Active-Organization': '11111111-1111-1111-1111-111111111111',
    });
    expect(descriptor.body).toBeUndefined();
  });

  it('adds correlation, idempotency, version precondition and body headers when supplied', () => {
    const descriptor = buildRequestDescriptor(
      'POST',
      '/api/v1/configuration/{key}/versions/{version}/approve',
      {
        activeOrganization: 'org-1',
        correlationId: 'corr-1',
        idempotencyKey: 'idem-1',
        ifMatch: 0,
      },
      { note: 'synthetic approval' },
    );
    expect(descriptor.headers['X-Correlation-Id']).toBe('corr-1');
    expect(descriptor.headers['Idempotency-Key']).toBe('idem-1');
    expect(descriptor.headers['If-Match']).toBe('0');
    expect(descriptor.headers['Content-Type']).toBe('application/json');
    expect(descriptor.body).toEqual({ note: 'synthetic approval' });
  });

  it('omits optional headers for empty identifiers and keeps a zero version', () => {
    const descriptor = buildRequestDescriptor('GET', '/api/v1/staff', {
      activeOrganization: 'org-1',
      correlationId: '',
      idempotencyKey: '',
      ifMatch: 0,
    });
    expect(descriptor.headers).not.toHaveProperty('X-Correlation-Id');
    expect(descriptor.headers).not.toHaveProperty('Idempotency-Key');
    expect(descriptor.headers['If-Match']).toBe('0');
    expect(descriptor.headers).not.toHaveProperty('Content-Type');
  });

  it('refuses any attempt to call a real application endpoint', () => {
    expect(() => refuseNetworkCall()).toThrow(/never calls application endpoints/);
  });
});
