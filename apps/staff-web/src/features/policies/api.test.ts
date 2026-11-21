import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import type { ConfigurationVersion } from '../admin/domain';
import {
  approvePolicyVersion,
  asPolicyTransportError,
  defineDictionaryField,
  getPolicyHistory,
  isValidCatalogFamily,
  isValidConfigKey,
  listDictionaryFamily,
  loadDictionaryVersions,
  loadPolicyVersions,
  proposePolicyVersion,
  toDictionaryVersions,
  toPolicyVersions,
} from './api';
import { EMPTY_POLICY_DRAFT, encodePolicyValue } from './domain';
import { alphaOrganizationId, holdReleaseKey, templateKey } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

/** Read the descriptor sent at one call index. */
function descriptorAt(call: number): RequestDescriptor {
  return sendMock.mock.calls[call]![0] as RequestDescriptor;
}

/** A fully populated administration configuration row. */
function configRow(overrides: Partial<ConfigurationVersion> = {}): ConfigurationVersion {
  return {
    organizationId: alphaOrganizationId,
    key: holdReleaseKey,
    facilityCode: 'MAIN',
    version: 3,
    value: 'Synthetic policy value',
    effectiveFrom: '2026-04-01',
    approved: true,
    approver: 'Synthetic named approver',
    ...overrides,
  };
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('toPolicyVersions', () => {
  it('maps approval, approver and the derived release mode', () => {
    const pending: ConfigurationVersion = {
      organizationId: alphaOrganizationId,
      key: holdReleaseKey,
      facilityCode: 'MAIN',
      version: 4,
      value: 'Synthetic pending policy value',
      effectiveFrom: '2026-04-01',
      approved: false,
    };
    const rows = toPolicyVersions([configRow(), pending], holdReleaseKey);
    expect(rows[0]).toEqual({
      organizationId: alphaOrganizationId,
      key: holdReleaseKey,
      version: 3,
      value: 'Synthetic policy value',
      effectiveFrom: '2026-04-01',
      approval: 'approved',
      approver: 'Synthetic named approver',
      mode: 'hold',
    });
    expect(rows[1]?.approval).toBe('pending');
    expect(rows[1]).not.toHaveProperty('approver');
  });

  it('defaults the mode for a template key', () => {
    expect(toPolicyVersions([configRow()], templateKey('soap'))[0]?.mode).toBe('immediate');
  });

  it('recovers the encoded mode, reason, expiry and named approver', () => {
    const encoded = encodePolicyValue({
      ...EMPTY_POLICY_DRAFT,
      value: 'Synthetic encoded value',
      approver: 'Synthetic named approver',
      mode: 'hold',
      reason: 'Synthetic review reason',
      expiry: '2026-08-01',
    });
    const row = toPolicyVersions([configRow({ value: encoded })], holdReleaseKey)[0];
    expect(row?.value).toBe('Synthetic encoded value');
    expect(row?.mode).toBe('hold');
    expect(row?.reason).toBe('Synthetic review reason');
    expect(row?.expiry).toBe('2026-08-01');
    expect(row?.proposedBy).toBe('Synthetic named approver');
  });
});

describe('toDictionaryVersions', () => {
  it('maps a populated catalog entry with its approver and civil date', () => {
    const rows = toDictionaryVersions(
      [
        {
          family: 'obstetric_history',
          code: 'LMP',
          version: 2,
          value: 'Synthetic LMP meaning',
          effectiveFrom: '2026-04-01T00:00:00Z',
          approvedBy: 'Synthetic steward',
        },
      ],
      'obstetric_history',
      alphaOrganizationId,
    );
    expect(rows[0]).toEqual({
      organizationId: alphaOrganizationId,
      key: 'obstetric_history.LMP',
      version: 2,
      value: 'Synthetic LMP meaning',
      effectiveFrom: '2026-04-01',
      approval: 'approved',
      approver: 'Synthetic steward',
    });
  });

  it('uses explicit fallbacks for a sparse catalog entry', () => {
    expect(
      toDictionaryVersions([{}], 'obstetric_history', alphaOrganizationId)[0],
    ).toEqual({
      organizationId: alphaOrganizationId,
      key: 'obstetric_history.',
      version: 0,
      value: '',
      effectiveFrom: '',
      approval: 'approved',
    });
  });
});

describe('read commands', () => {
  it('reads configuration history through the contract path', async () => {
    const response = { key: holdReleaseKey, entries: [] };
    sendMock.mockResolvedValueOnce(response);
    expect(await getPolicyHistory(holdReleaseKey, alphaOrganizationId)).toBe(response);
    expect(descriptorAt(0)).toMatchObject({
      method: 'GET',
      url: `/api/v1/configuration/${encodeURIComponent(holdReleaseKey)}/history`,
      headers: { 'X-Active-Organization': alphaOrganizationId },
    });
  });

  it('reads a catalog family through the contract path', async () => {
    sendMock.mockResolvedValueOnce([]);
    expect(await listDictionaryFamily('obstetric_history', alphaOrganizationId)).toEqual([]);
    expect(descriptorAt(0)).toMatchObject({
      method: 'GET',
      url: '/api/v1/catalogs/obstetric_history',
    });
  });

  it('projects loaded policy history into display versions', async () => {
    sendMock.mockResolvedValueOnce({
      key: holdReleaseKey,
      entries: [configRow({ version: 1, effectiveFrom: '2026-03-01T00:00:00Z' })],
    });
    const versions = await loadPolicyVersions(holdReleaseKey, alphaOrganizationId);
    expect(versions[0]?.version).toBe(1);
    expect(versions[0]?.effectiveFrom).toBe('2026-03-01');
    expect(versions[0]?.mode).toBe('hold');
  });

  it('projects a loaded catalog family into display versions', async () => {
    sendMock.mockResolvedValueOnce([
      { code: 'GRAVIDA', version: 1, value: 'Synthetic gravida', effectiveFrom: '2026-02-01T00:00:00Z' },
    ]);
    const versions = await loadDictionaryVersions('obstetric_history', alphaOrganizationId);
    expect(versions[0]?.key).toBe('obstetric_history.GRAVIDA');
    expect(versions[0]?.effectiveFrom).toBe('2026-02-01');
  });
});

describe('write commands', () => {
  const draft = { ...EMPTY_POLICY_DRAFT, value: 'Synthetic new value', effectiveFrom: '2026-07-01', code: 'LMP' };

  it('proposes a version with a UTC effective instant', async () => {
    sendMock.mockResolvedValueOnce({ version: 4 });
    await proposePolicyVersion(holdReleaseKey, draft, alphaOrganizationId);
    expect(descriptorAt(0)).toMatchObject({
      method: 'POST',
      url: '/api/v1/configuration',
      body: { key: holdReleaseKey, value: encodePolicyValue(draft), effectiveFrom: '2026-07-01T00:00:00Z' },
    });
  });

  it('approves a version with a note', async () => {
    sendMock.mockResolvedValueOnce({ version: 4 });
    await approvePolicyVersion(holdReleaseKey, 4, 'Synthetic approval note', alphaOrganizationId);
    expect(descriptorAt(0).url).toBe(
      `/api/v1/configuration/${encodeURIComponent(holdReleaseKey)}/versions/4/approve`,
    );
    expect(descriptorAt(0).body).toEqual({ note: 'Synthetic approval note' });
  });

  it('defines a dictionary field with an effective instant when one is given', async () => {
    sendMock.mockResolvedValueOnce({ code: 'LMP' });
    await defineDictionaryField('obstetric_history', draft, alphaOrganizationId);
    expect(descriptorAt(0).url).toBe('/api/v1/catalogs/obstetric_history/entries');
    expect(descriptorAt(0).body).toEqual({
      code: 'LMP',
      value: 'Synthetic new value',
      effectiveFrom: '2026-07-01T00:00:00Z',
    });
  });

  it('omits an absent effective instant from a dictionary definition', async () => {
    sendMock.mockResolvedValueOnce({ code: 'LMP' });
    await defineDictionaryField('obstetric_history', { ...draft, effectiveFrom: '' }, alphaOrganizationId);
    expect(descriptorAt(0).body).toEqual({ code: 'LMP', value: 'Synthetic new value' });
  });
});

describe('re-exported validation and error helpers', () => {
  it('validates bounded keys and families', () => {
    expect(isValidConfigKey('release.hold')).toBe(true);
    expect(isValidConfigKey('!')).toBe(false);
    expect(isValidCatalogFamily('obstetric_history')).toBe(true);
    expect(isValidCatalogFamily('Upper')).toBe(false);
  });

  it('preserves a typed transport error and normalizes other failures', () => {
    const typed = new TransportError('forbidden', {
      correlationId: 'corr-1',
      code: undefined,
      retryAfterSeconds: undefined,
    });
    expect(asPolicyTransportError(typed)).toBe(typed);
    expect(asPolicyTransportError(new Error('synthetic')).kind).toBe('server');
  });
});
