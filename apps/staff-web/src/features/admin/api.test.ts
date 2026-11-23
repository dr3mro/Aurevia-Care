import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  approveConfiguration,
  asAdminTransportError,
  auditEventsFromPage,
  auditQueryString,
  configurationVersionsFromHistory,
  createFacility,
  deactivateStaff,
  defineCatalogEntry,
  defineConfiguration,
  facilityFieldForCode,
  getConfiguration,
  getConfigurationHistory,
  getStaffHistory,
  inviteStaff,
  isValidCatalogFamily,
  isValidConfigKey,
  isValidUtcInstant,
  listCatalogFamily,
  listFacilities,
  listStaff,
  normalizeAuditOutcome,
  queryAuditEvents,
  staffFieldForCode,
  toStaffHistoryEvents,
  validateFacility,
  validateInvite,
  validationKeyForField,
} from './api';
import type { StaffHistoryResponse } from './api';
import { adminText } from './messages';
import { alphaOrganizationId, betaOrganizationId } from './fixtures';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  return must(sendMock.mock.calls[call])[0];
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

    await expect(
      createFacility({ code: 'LAB', name: 'Synthetic Lab' }, alphaOrganizationId),
    ).resolves.toEqual(created);

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

    const draft = {
      subject: 'synthetic.auditor',
      displayName: 'Synthetic Auditor',
      role: 'privacy_auditor',
    };
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

    await expect(deactivateStaff('synthetic.auditor', alphaOrganizationId)).resolves.toEqual(
      member,
    );

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/staff/synthetic.auditor/deactivate');
    expect(descriptor.body).toBeUndefined();
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('encodes a subject that contains reserved characters', async () => {
    sendMock.mockResolvedValueOnce({
      subject: 'a/b',
      displayName: 'Synthetic',
      status: 'active',
      roles: [],
    });

    await deactivateStaff('a/b', alphaOrganizationId);

    expect(descriptorAt(0).url).toBe('/api/v1/staff/a%2Fb/deactivate');
  });

  it('reads immutable history on the contract path', async () => {
    const view = history();
    sendMock.mockResolvedValueOnce(view);

    await expect(getStaffHistory('synthetic.reception', alphaOrganizationId)).resolves.toEqual(
      view,
    );

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
    const valid = {
      subject: 'synthetic.auditor',
      displayName: 'Synthetic Auditor',
      role: 'reception',
    };
    expect(validateInvite(valid)).toEqual({});
    expect(validateInvite({ subject: 'Bad Subject', displayName: '', role: 'superuser' })).toEqual({
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
    const granted = must(events[1]);
    expect(granted.kind === 'role_granted' ? granted.role : '').toBe('reception');
    expect(granted.actor).toBe('synthetic.admin');
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
    const deactivation = must(events[1]);
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

    const events = toStaffHistoryEvents(view, alphaOrganizationId, 'ar');
    const first = must(events[0]);
    expect(first.kind === 'deactivated' ? first.reason : '').toBe(
      adminText('ar', 'reasonNotAvailable'),
    );
  });

  it('returns no events for an empty history', () => {
    expect(
      toStaffHistoryEvents(history({ memberships: [], roleGrants: [] }), alphaOrganizationId, 'en'),
    ).toEqual([]);
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

describe('configuration API client', () => {
  const key = 'facility.MAIN.booking';

  it('reads one effective configuration entry by key', async () => {
    const entry = { key, version: 2, value: 'synthetic value', status: 'approved' };
    sendMock.mockResolvedValueOnce(entry);

    await expect(getConfiguration(key, alphaOrganizationId)).resolves.toEqual(entry);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/configuration/facility.MAIN.booking');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('encodes a key that contains reserved URL characters', async () => {
    sendMock.mockResolvedValueOnce({ key: 'a/b c', version: 1 });
    await getConfiguration('a/b c', alphaOrganizationId);
    expect(descriptorAt(0).url).toBe('/api/v1/configuration/a%2Fb%20c');
  });

  it('reads the append-only history by key', async () => {
    const history = { key, entries: [] };
    sendMock.mockResolvedValueOnce(history);
    await expect(getConfigurationHistory(key, betaOrganizationId)).resolves.toEqual(history);
    expect(descriptorAt(0).url).toBe('/api/v1/configuration/facility.MAIN.booking/history');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });

  it('defines a new version with a bounded body and a fresh idempotency key', async () => {
    const created = { key, version: 3, status: 'pending' };
    sendMock.mockResolvedValueOnce(created);

    await expect(
      defineConfiguration(
        { key, value: 'synthetic draft', effectiveFrom: '2026-07-01T00:00:00Z' },
        alphaOrganizationId,
      ),
    ).resolves.toEqual(created);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/configuration');
    expect(descriptor.body).toEqual({
      key,
      value: 'synthetic draft',
      effectiveFrom: '2026-07-01T00:00:00Z',
    });
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('omits an absent effective instant from a define body', async () => {
    sendMock.mockResolvedValueOnce({ key, version: 3 });
    await defineConfiguration({ key, value: 'synthetic draft' }, alphaOrganizationId);
    expect(descriptorAt(0).body).toEqual({ key, value: 'synthetic draft' });
  });

  it('approves one version with an approval note and a fresh idempotency key', async () => {
    sendMock.mockResolvedValueOnce({ key, version: 3, status: 'approved' });

    await approveConfiguration(key, 3, 'synthetic approval', alphaOrganizationId);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/configuration/facility.MAIN.booking/versions/3/approve');
    expect(descriptor.body).toEqual({ note: 'synthetic approval' });
    expect(descriptor.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('lists an effective catalog family and defines a pending entry', async () => {
    const entries = [{ family: 'diagnosis', code: 'SYN', version: 1 }];
    sendMock.mockResolvedValueOnce(entries);
    sendMock.mockResolvedValueOnce({ key: 'diagnosis.SYN', version: 2, status: 'pending' });

    await expect(listCatalogFamily('diagnosis', alphaOrganizationId)).resolves.toEqual(entries);
    expect(descriptorAt(0).url).toBe('/api/v1/catalogs/diagnosis');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    await defineCatalogEntry(
      'diagnosis',
      { code: 'SYN', value: 'synthetic entry', effectiveFrom: '2026-08-01T00:00:00Z' },
      alphaOrganizationId,
    );
    expect(descriptorAt(1).method).toBe('POST');
    expect(descriptorAt(1).url).toBe('/api/v1/catalogs/diagnosis/entries');
    expect(descriptorAt(1).body).toEqual({
      code: 'SYN',
      value: 'synthetic entry',
      effectiveFrom: '2026-08-01T00:00:00Z',
    });
  });

  it('maps API history into the domain versions shown by the editor', () => {
    const versions = configurationVersionsFromHistory(
      {
        key,
        entries: [
          {
            key,
            version: 1,
            value: 'synthetic approved',
            status: 'approved',
            effectiveFrom: '2026-01-01T08:00:00Z',
            approvedBy: 'synthetic.approver',
          },
          { key, version: 2, value: 'synthetic pending', status: 'pending', effectiveFrom: null },
        ],
      },
      alphaOrganizationId,
    );

    expect(versions).toEqual([
      {
        organizationId: alphaOrganizationId,
        key,
        facilityCode: key,
        version: 1,
        value: 'synthetic approved',
        effectiveFrom: '2026-01-01',
        approved: true,
        approver: 'synthetic.approver',
      },
      {
        organizationId: alphaOrganizationId,
        key,
        facilityCode: key,
        version: 2,
        value: 'synthetic pending',
        effectiveFrom: '',
        approved: false,
        approver: undefined,
      },
    ]);
  });

  it('handles a history response with no key and no entries', () => {
    expect(configurationVersionsFromHistory({}, alphaOrganizationId)).toEqual([]);
    expect(
      must(configurationVersionsFromHistory({ entries: [{ version: 1 }] }, alphaOrganizationId)[0])
        .key,
    ).toBe('');
  });

  it('falls back to the history key and empty fields for a sparse API entry', () => {
    const versions = configurationVersionsFromHistory({ key, entries: [{}] }, alphaOrganizationId);
    expect(versions[0]).toEqual({
      organizationId: alphaOrganizationId,
      key,
      facilityCode: key,
      version: 0,
      value: '',
      effectiveFrom: '',
      approved: false,
      approver: undefined,
    });
  });
});

describe('audit API client', () => {
  it('builds a query string in contract order from only supplied filters', () => {
    expect(
      auditQueryString({
        cursor: 'seq-9',
        limit: 25,
        action: 'CONFIG_CREATE',
        outcome: 'SUCCESS',
        resourceType: 'configuration',
        from: '2026-03-01T00:00:00Z',
        to: '2026-03-08T00:00:00Z',
      }),
    ).toBe(
      'cursor=seq-9&limit=25&action=CONFIG_CREATE&outcome=SUCCESS&resourceType=configuration' +
        '&from=2026-03-01T00%3A00%3A00Z&to=2026-03-08T00%3A00%3A00Z',
    );
  });

  it('omits empty and absent filters from the query string', () => {
    expect(auditQueryString({ action: '', limit: 0 })).toBe('');
  });

  it('queries audit events on the contract path with filters and the tenant header', async () => {
    const page = { events: [], nextCursor: 'seq-3' };
    sendMock.mockResolvedValueOnce(page);

    await expect(
      queryAuditEvents({ action: 'AUDIT_READ', outcome: 'DENIED' }, betaOrganizationId),
    ).resolves.toEqual(page);

    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/audit-events?action=AUDIT_READ&outcome=DENIED');
    expect(descriptor.headers['X-Active-Organization']).toBe(betaOrganizationId);
  });

  it('queries without any filters on the bare contract path', async () => {
    sendMock.mockResolvedValueOnce({ events: [] });
    await queryAuditEvents({}, alphaOrganizationId);
    expect(descriptorAt(0).url).toBe('/api/v1/audit-events');
  });

  it('validates only real UTC instants', () => {
    expect(isValidUtcInstant('2026-03-08T06:30:00Z')).toBe(true);
    expect(isValidUtcInstant('2026-03-08')).toBe(false);
    expect(isValidUtcInstant('not-a-date')).toBe(false);
    expect(isValidUtcInstant('2026-99-99T00:00:00Z')).toBe(false);
    expect(isValidUtcInstant('2026-02-30T00:00:00Z')).toBe(false);
  });

  it('validates configuration keys and catalog families before placing them in a path', () => {
    expect(isValidConfigKey('facility.MAIN.booking')).toBe(true);
    expect(isValidConfigKey('a')).toBe(false);
    expect(isValidConfigKey('bad key!')).toBe(false);
    expect(isValidCatalogFamily('referral_partner')).toBe(true);
    expect(isValidCatalogFamily('1bad')).toBe(false);
    expect(isValidCatalogFamily('bad-family')).toBe(false);
  });

  it('normalizes every documented outcome and a missing outcome', () => {
    expect(normalizeAuditOutcome('SUCCESS')).toBe('SUCCESS');
    expect(normalizeAuditOutcome('denied')).toBe('DENIED');
    expect(normalizeAuditOutcome('NOT_FOUND')).toBe('NOT_FOUND');
    expect(normalizeAuditOutcome('CONFLICT')).toBe('CONFLICT');
    expect(normalizeAuditOutcome(undefined)).toBe('DENIED');
  });

  it('maps an API audit page into the domain rows shown by the list', () => {
    const events = auditEventsFromPage(
      {
        events: [
          {
            id: 'audit-1',
            occurredAt: '2026-03-08T06:30:00Z',
            action: 'CONFIG_CREATE',
            outcome: 'SUCCESS',
            seq: 7,
          },
          { id: 'audit-2', outcome: 'CONFLICT' },
        ],
        nextCursor: 'seq-5',
      },
      alphaOrganizationId,
      'en',
    );

    expect(events).toHaveLength(2);
    expect(events[0]).toMatchObject({
      organizationId: alphaOrganizationId,
      sequence: 7,
      action: 'CONFIG_CREATE',
      outcome: 'SUCCESS',
      occurredAt: '2026-03-08T06:30:00Z',
      actor: adminText('en', 'systemActor'),
      purpose: adminText('en', 'unknown'),
    });
    expect(must(events[1]).outcome).toBe('CONFLICT');
    expect(must(events[1]).occurredAt).toBe('');
  });

  it('returns no rows for an audit page without an events field', () => {
    expect(auditEventsFromPage({}, alphaOrganizationId, 'en')).toEqual([]);
  });

  it('uses localized fallbacks for missing audit fields under Arabic', () => {
    const events = auditEventsFromPage(
      { events: [{ outcome: 'SUCCESS' }] },
      alphaOrganizationId,
      'ar',
    );
    expect(must(events[0]).actor).toBe(adminText('ar', 'systemActor'));
    expect(must(events[0]).scope).toBe(adminText('ar', 'unknown'));
    expect(must(events[0]).correlationId).toBe(adminText('ar', 'unknown'));
  });
});
