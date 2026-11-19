import { tenantKey } from './tenancy';
import type {
  DeviceToken,
  InvalidTokenEvent,
  InvalidTokenReason,
  OrganizationId,
} from './types';

/**
 * List devices eligible for dispatch at this moment.
 *
 * Only active devices in the active organization are returned; invalid and
 * revoked registrations are excluded on every evaluation, never cached.
 *
 * @param devices tenant-keyed device registry
 * @param organizationId active organization
 * @returns deliverable registrations
 */
export function eligibleTokens(
  devices: ReadonlyMap<string, DeviceToken>,
  organizationId: OrganizationId,
): DeviceToken[] {
  const prefix = `${organizationId}\u0000`;
  return [...devices.entries()]
    .filter(([key, token]) => key.startsWith(prefix) && token.state === 'active')
    .map(([, token]) => token);
}

/**
 * Return a revoked copy of a device without mutating the registration.
 *
 * @param token current registration
 * @returns the revoked registration
 */
export function revokeToken(token: DeviceToken): DeviceToken {
  return { ...token, state: 'revoked' };
}

/**
 * Invalidate a rejected token and emit a log event without the token value.
 *
 * @param token current registration
 * @param reason non-sensitive provider disposition
 * @param at server instant of the disposition
 * @returns the new registration and its log event
 */
export function invalidateToken(
  token: DeviceToken,
  reason: InvalidTokenReason,
  at: string,
): { token: DeviceToken; event: InvalidTokenEvent } {
  return {
    token: { ...token, state: 'invalid' },
    event: { deviceId: token.id, patientId: token.patientId, reason, at },
  };
}

/**
 * Store a device under its tenant key for the active organization only.
 *
 * @param devices tenant-keyed registry to add to
 * @param organizationId active organization
 * @param token new active registration
 * @returns a new registry containing the registration
 */
export function withRegisteredDevice(
  devices: ReadonlyMap<string, DeviceToken>,
  organizationId: OrganizationId,
  token: DeviceToken,
): Map<string, DeviceToken> {
  const next = new Map(devices);
  next.set(tenantKey(organizationId, token.id), token);
  return next;
}
