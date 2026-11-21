import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  approveServiceProfile,
  getServiceProfile,
  proposeServiceProfile,
  setUnitEnabled,
  toServiceProfile,
} from './api';
import type { ProfileDraft } from './types';
import { alphaOrganizationId, alphaProfile, alphaUnitId, profileResponse } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function draft(overrides: Partial<ProfileDraft> = {}): ProfileDraft {
  return {
    serviceHours: 'Mon-Fri 09:00-17:00',
    responseTargetUrgent: '15 minutes',
    responseTargetRoutine: '1 business day',
    topics: 'appointments',
    urgentContactInstructions: 'Call the synthetic on-call phone',
    queueOwner: 'Synthetic Unit Lead',
    backupCoverage: 'Synthetic Backup Lead',
    approver: 'Synthetic Clinical Lead',
    effectiveFrom: '2026-03-10',
    ...overrides,
  };
}

describe('unit service profile API client (MSG-06, ADM-01)', () => {
  it('reads one tenant-scoped service profile on its own unit path', async () => {
    const response = profileResponse(alphaProfile);
    sendMock.mockResolvedValueOnce(response);

    await expect(getServiceProfile(alphaUnitId, alphaOrganizationId)).resolves.toEqual(response);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/units/synthetic-unit-001/service-profile');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['If-Match']).toBeUndefined();
    expect(descriptor.body).toBeUndefined();
  });

  it('proposes a change with the full draft, a version precondition and a fresh key', async () => {
    const response = profileResponse(alphaProfile);
    sendMock.mockResolvedValueOnce(response);
    const changes = draft();

    await expect(
      proposeServiceProfile(alphaUnitId, 3, changes, alphaOrganizationId),
    ).resolves.toEqual(response);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/units/synthetic-unit-001/service-profile');
    expect(descriptor.body).toEqual(changes);
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.headers['Idempotency-Key']).toBeTruthy();
    expect(descriptor.headers['Content-Type']).toBe('application/json');
  });

  it('approves a named version through the approval sub-path', async () => {
    sendMock.mockResolvedValueOnce(profileResponse(alphaProfile));

    await approveServiceProfile(alphaUnitId, 4, alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe(
      '/api/v1/units/synthetic-unit-001/service-profile/versions/4/approve',
    );
    expect(descriptor.headers['If-Match']).toBe('4');
    expect(descriptor.headers['Idempotency-Key']).toBeTruthy();
    expect(descriptor.body).toBeUndefined();
  });

  it.each([true, false])('sends the enabled flag %s with a version guard', async (enabled) => {
    sendMock.mockResolvedValueOnce(profileResponse(alphaProfile));

    await setUnitEnabled(alphaUnitId, 3, enabled, 'Synthetic Unit Lead', alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/units/synthetic-unit-001/enabled');
    expect(descriptor.body).toEqual({ enabled });
    expect(descriptor.headers['If-Match']).toBe('3');
  });

  it('refuses to enable a unit with no named owner before sending a request', async () => {
    const rejected = setUnitEnabled(alphaUnitId, 3, true, '  ', alphaOrganizationId);
    await expect(rejected).rejects.toMatchObject({
      kind: 'invalid',
      code: 'OWNER_REQUIRED',
    });
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('propagates a typed transport failure unchanged', async () => {
    const failure = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'STALE',
      retryAfterSeconds: undefined,
    });
    sendMock.mockRejectedValueOnce(failure);

    await expect(getServiceProfile(alphaUnitId, alphaOrganizationId)).rejects.toBe(failure);
  });

  it('projects the wire response into the tenant-scoped view type', () => {
    expect(toServiceProfile(profileResponse(alphaProfile))).toEqual(alphaProfile);
  });
});
