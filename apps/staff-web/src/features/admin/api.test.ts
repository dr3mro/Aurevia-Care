import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import type { Locale } from '../../i18n/messages';
import {
  asAdminTransportError,
  createFacility,
  deactivateStaff,
  facilityFieldForCode,
  getStaffHistory,
  inviteStaff,
  listFacilities,
  listStaff,
  staffFieldForCode,
  toStaffHistoryEvents,
  validateFacility,
  validateInvite,
  validationKeyForField,
} from './api';
import type { StaffHistoryResponse } from './api';
import { adminText } from './messages';
import { alphaOrganizationId, betaOrganizationId } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

function history(overrides: Partial<StaffHistoryResponse> = {}): StaffHistoryResponse {
  return {
    subject: 'synthetic.reception',
    memberships: [
      {
        id: 'membership-1',
        status: 'active',
        validFrom: '2026-01-05T08:00:00Z',
        validTo: null,
        createdAt: '2026-01-05T08:00:00Z',
      },
    ],
    roleGrants: [
      {
        id: 'grant-1',
        roleKey: 'reception',
        grantedBy: 'synthetic.admin',
        validFrom: '2026-01-06T08:00:00Z',
        validTo: null,
      },
    ],
    ...overrides,
  };
}

describe('facility API client', () => {
  it('lists facilities on the contract path with only the tenant header', async () => {
    const page = { items: [], nextCursor: null };
    sendMock.mockResolvedValueOnce(page);

    await expect(listFacilities(undefined, alphaOrganizationId)).resolves.toEqual(page);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/facilities');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Idempotency-Key']).toBeUndefined();
  });

  it('appends an opaque cursor and a bounded limit when supplied', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });

    await listFacilities('MAIN:id-1', alphaOrganizationId, 25);

    expect(descriptorAt(0).url).toBe('/api/v1/facilities?cursor=MAIN%3Aid-1&limit=25');
  });

  it('creates a facility with a fresh idempotency key and no tenant field in the body', async () => {
    const created = { organizationId: alphaOrganizationId, code: 'LAB', name: 'Synthetic Lab' };
    sendMock.mockResolvedValueOnce(created);

    await expect(createFacility({ code: 'LAB', name: 'Synthetic Lab' }, alphaOrganizationId)).resolves.toEqual(created);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/facilities');
    expect(descriptor.body).toEqual({ code: 'LAB', name: 'Synthetic Lab' });
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('propagates a typed transport failure unchanged', async () => {
    const failure = new TransportError('forbidden', {
      correlationId: 'corr-facilities',
      code: 'FORBIDDEN',
      retryAfterSeconds: undefined,
    });
    sendMock.mockRejectedValueOnce(failure);

    await expect(listFacilities(undefined, alphaOrganizationId)).rejects.toBe(failure);
  });
});

describe('staff API client', () => {
  it('lists staff with a status filter and a cursor', async () => {
    const page = { items: [], nextCursor: null };
    sendMock.mockResolvedValueOnce(page);

    await expect(listStaff('cursor-2', 'deactivated', alphaOrganizationId)).resolves.toEqual(page);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/staff?cursor=cursor-2&status=deactivated');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('lists active staff without query parameters by default', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });

    await listStaff(undefined, undefined, betaOrganizationId);

    expect(descriptorAt(0).url).toBe('/api/v1/staff');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });

  it('invites with one bounded role and a fresh idempotency key', async () => {
    const member = {
      subject: 'synthetic.auditor',
      displayName: 'Synthetic Auditor',
      status: 'active',
      roles: ['privacy_auditor'],
    };
    sendMock.mockResolvedValueOnce(member);

    const draft = { subject: 'synthetic.auditor', displayName: 'Synthetic Auditor', role: 'privacy_auditor' };
    await expect(inviteStaff(draft, alphaOrganizationId)).resolves.toEqual(member);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/staff');
    expect(descriptor.body).toEqual(draft);
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('deactivates by posting to the encoded subject path without a body', async () => {
    const member = {
      subject: 'synthetic.auditor',
      displayName: 'Synthetic Auditor',
      status: 'deactivated',
      roles: [],
    };
    sendMock.mockResolvedValueOnce(member);

    await expect(deactivateStaff('synthetic.auditor', alphaOrganizationId)).resolves.toEqual(member);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/staff/synthetic.auditor/deactivate');
    expect(descriptor.body).toBeUndefined();
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('encodes a subject that contains reserved characters', async () => {
    sendMock.mockResolvedValueOnce({ subject: 'a/b', displayName: 'Synthetic', status: 'active', roles: [] });

    await deactivateStaff('a/b', alphaOrganizationId);

    expect(descriptorAt(0).url).toBe('/api/v1/staff/a%2Fb/deactivate');
  });

  it('reads immutable history on the contract path', async () => {
    const view = history();
    sendMock.mockResolvedValueOnce(view);

    await expect(getStaffHistory('synthetic.reception', alphaOrganizationId)).resolves.toEqual(view);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/staff/synthetic.reception/history');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });
});

describe('admin form validation', () => {
  it('accepts a valid facility draft', () => {
    expect(validateFacility({ code: 'LAB-1', name: 'Synthetic Lab' })).toEqual({});
  });

  it('reports an invalid facility code and an empty name together', () => {
    expect(validateFacility({ code: 'lab', name: '   ' })).toEqual({
      code: 'errorFacilityCode',
      name: 'errorFacilityName',
    });
  });

  it('reports a name longer than the contract maximum', () => {
    expect(validateFacility({ code: 'LAB', name: 'x'.repeat(201) })).toEqual({
      name: 'errorFacilityName',
    });
  });

  it('accepts a valid invitation and rejects each invalid field', () => {
    const valid = { subject: 'synthetic.auditor', displayName: 'Synthetic Auditor', role: 'reception' };
    expect(validateInvite(valid)).toEqual({});
    expect(
      validateInvite({ subject: 'Bad Subject', displayName: '', role: 'superuser' }),
    ).toEqual({
      subject: 'errorStaffSubject',
      displayName: 'errorStaffDisplayName',
      role: 'errorStaffRole',
    });
    expect(
      validateInvite({
        subject: 'synthetic.auditor',
        displayName: 'x'.repeat(201),
        role: 'reception',
      }),
    ).toEqual({ displayName: 'errorStaffDisplayName' });
  });

  it('maps server validation codes to the field that caused them', () => {
    expect(facilityFieldForCode('INVALID_CODE')).toBe('code');
    expect(facilityFieldForCode('INVALID_NAME')).toBe('name');
    expect(facilityFieldForCode('UNKNOWN')).toBeUndefined();
    expect(facilityFieldForCode(undefined)).toBeUndefined();
    expect(staffFieldForCode('INVALID_SUBJECT')).toBe('subject');
    expect(staffFieldForCode('INVALID_DISPLAY_NAME')).toBe('displayName');
    expect(staffFieldForCode('INVALID_ROLE')).toBe('role');
    expect(staffFieldForCode('UNKNOWN')).toBeUndefined();
    expect(staffFieldForCode(undefined)).toBeUndefined();
  });

  it('gives every server-mapped field its localized validation message key', () => {
    expect(validationKeyForField('code')).toBe('errorFacilityCode');
    expect(validationKeyForField('name')).toBe('errorFacilityName');
    expect(validationKeyForField('subject')).toBe('errorStaffSubject');
    expect(validationKeyForField('displayName')).toBe('errorStaffDisplayName');
    expect(validationKeyForField('role')).toBe('errorStaffRole');
  });
});

describe('staff history mapping', () => {
  it('folds memberships and role grants into append-only domain events in sequence order', () => {
    const events = toStaffHistoryEvents(history(), alphaOrganizationId, 'en');

    expect(events.map((event) => event.kind)).toEqual(['invited', 'role_granted']);
    expect(events.map((event) => event.sequence)).toEqual([1, 2]);
    expect(events.every((event) => event.organizationId === alphaOrganizationId)).toBe(true);
    expect(events[1]!.kind === 'role_granted' ? events[1]!.role : '').toBe('reception');
    expect(events[1]!.actor).toBe('synthetic.admin');
  });

  it('keeps a revoked membership as its own deactivation event with a reason', () => {
    const view = history({
      memberships: [
        {
          id: 'membership-1',
          status: 'active',
          validFrom: '2026-01-05T08:00:00Z',
          validTo: '2026-03-01T08:00:00Z',
          createdAt: '2026-01-05T08:00:00Z',
        },
        {
          id: 'membership-2',
          status: 'revoked',
          validFrom: '2026-03-01T08:00:00Z',
          validTo: null,
          createdAt: '2026-03-01T08:00:00Z',
        },
      ],
      roleGrants: [],
    });

    const events = toStaffHistoryEvents(view, betaOrganizationId, 'en');
    expect(events.map((event) => event.kind)).toEqual(['invited', 'deactivated']);
    const deactivation = events[1]!;
    expect(deactivation.kind === 'deactivated' ? deactivation.reason : '').toBe(
      adminText('en', 'reasonNotAvailable'),
    );
    expect(events.every((event) => event.organizationId === betaOrganizationId)).toBe(true);
  });

  it('localizes the fallback reason for the active locale', () => {
    const view = history({
      memberships: [
        {
          id: 'membership-2',
          status: 'revoked',
          validFrom: '2026-03-01T08:00:00Z',
          validTo: null,
          createdAt: '2026-03-01T08:00:00Z',
        },
      ],
      roleGrants: [],
    });

    const events = toStaffHistoryEvents(view, alphaOrganizationId, 'ar' as Locale);
    expect(events[0]!.kind === 'deactivated' ? events[0]!.reason : '').toBe(
      adminText('ar', 'reasonNotAvailable'),
    );
  });

  it('returns no events for an empty history', () => {
    expect(toStaffHistoryEvents(history({ memberships: [], roleGrants: [] }), alphaOrganizationId, 'en')).toEqual([]);
  });
});

describe('asAdminTransportError', () => {
  it('passes an existing typed error through unchanged', () => {
    const failure = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'CONFLICT',
      retryAfterSeconds: undefined,
    });
    expect(asAdminTransportError(failure)).toBe(failure);
  });

  it('normalizes an unexpected rejection to a server error with no correlation id', () => {
    const error = asAdminTransportError(new Error('unexpected'));
    expect(error.kind).toBe('server');
    expect(error.correlationId).toBeUndefined();
    expect(error.code).toBeUndefined();
  });
});
