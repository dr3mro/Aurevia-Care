import type { DeviceToken } from './types';
import { eligibleTokens, invalidateToken, revokeToken, withRegisteredDevice } from './devices';
import { tenantKey } from './tenancy';

/** S-G3-01: only active devices in the active tenant receive pushes (PAT-01, ARC-02). */
const token = (id: string, state: DeviceToken['state']): DeviceToken => ({
  id,
  patientId: 'patient-1',
  state,
  token: `synthetic-${id}`,
});

describe('eligibleTokens', () => {
  it('keeps only active tokens of the active organization', () => {
    const devices = new Map<string, DeviceToken>([
      [tenantKey('org-a', 'dev-1'), token('dev-1', 'active')],
      [tenantKey('org-a', 'dev-2'), token('dev-2', 'revoked')],
      [tenantKey('org-a', 'dev-3'), token('dev-3', 'invalid')],
      [tenantKey('org-b', 'dev-4'), token('dev-4', 'active')],
    ]);
    expect(eligibleTokens(devices, 'org-a')).toEqual([token('dev-1', 'active')]);
    expect(eligibleTokens(devices, 'org-b')).toEqual([token('dev-4', 'active')]);
    expect(eligibleTokens(devices, 'org-c')).toEqual([]);
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
