import type { DeviceToken } from './types';
import {
  eligibleTokens,
  invalidateToken,
  revokeToken,
  withInvalidatedDevice,
  withRegisteredDevice,
  withRevokedDevice,
} from './devices';
import { tenantKey } from './tenancy';

/** S-G3-01: only active devices of the verified patient in the active tenant receive pushes (PAT-01, ARC-02). */
const token = (id: string, state: DeviceToken['state'], patientId = 'patient-1'): DeviceToken => ({
  id,
  patientId,
  state,
  token: `synthetic-${id}`,
});

describe('eligibleTokens', () => {
  it('keeps only active tokens of the active organization for the verified patient', () => {
    const devices = new Map<string, DeviceToken>([
      [tenantKey('org-a', 'dev-1'), token('dev-1', 'active')],
      [tenantKey('org-a', 'dev-2'), token('dev-2', 'revoked')],
      [tenantKey('org-a', 'dev-3'), token('dev-3', 'invalid')],
      [tenantKey('org-b', 'dev-4'), token('dev-4', 'active')],
    ]);
    expect(eligibleTokens(devices, 'org-a', 'patient-1')).toEqual([token('dev-1', 'active')]);
    expect(eligibleTokens(devices, 'org-b', 'patient-1')).toEqual([token('dev-4', 'active')]);
    expect(eligibleTokens(devices, 'org-c', 'patient-1')).toEqual([]);
  });

  it('never selects another patient device inside the same organization', () => {
    const devices = new Map<string, DeviceToken>([
      [tenantKey('org-a', 'dev-1'), token('dev-1', 'active', 'patient-1')],
      [tenantKey('org-a', 'dev-2'), token('dev-2', 'active', 'patient-2')],
    ]);
    expect(eligibleTokens(devices, 'org-a', 'patient-1')).toEqual([
      token('dev-1', 'active', 'patient-1'),
    ]);
    expect(eligibleTokens(devices, 'org-a', 'patient-2')).toEqual([
      token('dev-2', 'active', 'patient-2'),
    ]);
    expect(eligibleTokens(devices, 'org-a', 'patient-3')).toEqual([]);
  });
});

describe('withRegisteredDevice', () => {
  it('stores a device under the active tenant key without touching a peer tenant', () => {
    const peerKey = tenantKey('org-b', 'dev-1');
    const source = new Map<string, DeviceToken>([[peerKey, token('dev-1', 'active')]]);
    const next = withRegisteredDevice(source, 'org-a', token('dev-1', 'active'));
    expect(next.get(tenantKey('org-a', 'dev-1'))).toEqual(token('dev-1', 'active'));
    expect(next.get(peerKey)).toEqual(token('dev-1', 'active'));
    expect(source.has(tenantKey('org-a', 'dev-1'))).toBe(false);
  });

  it('cannot rebind a device id already owned by another patient in the same tenant', () => {
    const source = new Map<string, DeviceToken>([
      [tenantKey('org-a', 'dev-1'), token('dev-1', 'active', 'patient-2')],
    ]);
    const next = withRegisteredDevice(source, 'org-a', token('dev-1', 'active', 'patient-1'));
    expect(next.get(tenantKey('org-a', 'dev-1'))).toEqual(token('dev-1', 'active', 'patient-2'));
  });
});

describe('device state transitions', () => {
  it('returns a new revoked token without mutating the source', () => {
    const original = token('dev-1', 'active');
    const revoked = revokeToken(original);
    expect(revoked.state).toBe('revoked');
    expect(original.state).toBe('active');
  });

  it('invalidates a token and emits a log event without the token value', () => {
    const original = token('dev-1', 'active');
    const result = invalidateToken(original, 'provider_rejected', '2026-11-19T10:00:00Z');
    expect(result.token.state).toBe('invalid');
    expect(result.event).toEqual({
      deviceId: 'dev-1',
      patientId: 'patient-1',
      reason: 'provider_rejected',
      at: '2026-11-19T10:00:00Z',
    });
    expect(JSON.stringify(result.event)).not.toContain('synthetic-dev-1');
  });
});

describe('persisted revocation and invalidation', () => {
  it('a stored revocation removes the device from dispatch eligibility', () => {
    const source = new Map<string, DeviceToken>([
      [tenantKey('org-a', 'dev-1'), token('dev-1', 'active')],
    ]);
    const revoked = withRevokedDevice(source, 'org-a', 'dev-1', 'patient-1');
    expect(revoked.get(tenantKey('org-a', 'dev-1'))?.state).toBe('revoked');
    expect(eligibleTokens(revoked, 'org-a', 'patient-1')).toEqual([]);
    expect(eligibleTokens(source, 'org-a', 'patient-1')).toEqual([token('dev-1', 'active')]);
  });

  it('a stored invalidation removes the device from dispatch eligibility', () => {
    const source = new Map<string, DeviceToken>([
      [tenantKey('org-a', 'dev-1'), token('dev-1', 'active')],
    ]);
    const invalidated = withInvalidatedDevice(source, 'org-a', 'dev-1', 'patient-1');
    expect(invalidated.get(tenantKey('org-a', 'dev-1'))?.state).toBe('invalid');
    expect(eligibleTokens(invalidated, 'org-a', 'patient-1')).toEqual([]);
    expect(eligibleTokens(source, 'org-a', 'patient-1')).toEqual([token('dev-1', 'active')]);
  });

  it('does not change a peer tenant or a missing device', () => {
    const peerKey = tenantKey('org-b', 'dev-1');
    const source = new Map<string, DeviceToken>([[peerKey, token('dev-1', 'active')]]);
    const revoked = withRevokedDevice(source, 'org-a', 'dev-1', 'patient-1');
    expect(revoked.get(peerKey)).toEqual(token('dev-1', 'active'));
    expect(revoked.has(tenantKey('org-a', 'dev-1'))).toBe(false);
    expect(withInvalidatedDevice(source, 'org-a', 'missing', 'patient-1')).toEqual(source);
  });

  it('never revokes or invalidates a device owned by another patient in the same tenant', () => {
    const foreign = token('dev-2', 'active', 'patient-2');
    const source = new Map<string, DeviceToken>([
      [tenantKey('org-a', 'dev-1'), token('dev-1', 'active', 'patient-1')],
      [tenantKey('org-a', 'dev-2'), foreign],
    ]);
    const revoked = withRevokedDevice(source, 'org-a', 'dev-2', 'patient-1');
    const invalidated = withInvalidatedDevice(source, 'org-a', 'dev-2', 'patient-1');
    expect(revoked.get(tenantKey('org-a', 'dev-2'))).toEqual(foreign);
    expect(invalidated.get(tenantKey('org-a', 'dev-2'))).toEqual(foreign);
    expect(revoked.get(tenantKey('org-a', 'dev-1'))?.state).toBe('active');
  });
});
