import { tenantKey } from './tenancy';
import type {
  DeviceState,
  DeviceToken,
  InvalidTokenEvent,
  InvalidTokenReason,
  OrganizationId,
} from './types';

/**
 * List devices eligible for dispatch at this moment.
 *
 * Only active devices of the verified patient in the active organization are
 * returned; another patient's device in the same tenant is never a recipient,
 * and invalid or revoked registrations are excluded on every evaluation, never
 * cached.
 *
 * @param devices tenant-keyed device registry
 * @param organizationId active organization
 * @param patientId verified patient whose devices may receive a push
 * @returns deliverable registrations for this patient
 */
export function eligibleTokens(
  devices: ReadonlyMap<string, DeviceToken>,
  organizationId: OrganizationId,
  patientId: string,
): DeviceToken[] {
  const prefix = `${organizationId}\u0000`;
  return [...devices.entries()]
    .filter(
      ([key, token]) =>
        key.startsWith(prefix) && token.patientId === patientId && token.state === 'active',
    )
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
 * Persist a device state change under its active tenant key.
 *
 * A key that does not exist in this organization is left unchanged, so a
 * guessed device id cannot create a registration or mutate a peer tenant.
 *
 * @param devices tenant-keyed registry to update
 * @param organizationId active organization
 * @param deviceId organization-local device identifier
 * @param state new delivery state
 * @returns a new registry containing the updated registration
 */
function withDeviceState(
  devices: ReadonlyMap<string, DeviceToken>,
  organizationId: OrganizationId,
  deviceId: string,
  state: DeviceState,
): Map<string, DeviceToken> {
  const key = tenantKey(organizationId, deviceId);
  const current = devices.get(key);
  if (!current) {
    return new Map(devices);
  }
  const next = new Map(devices);
  next.set(key, { ...current, state });
  return next;
}

/**
 * Persist a patient's revocation so the device stops receiving future pushes.
 *
 * @param devices tenant-keyed registry to update
 * @param organizationId active organization
 * @param deviceId organization-local device identifier
 * @returns a new registry with the device revoked when it is in this tenant
 */
export function withRevokedDevice(
  devices: ReadonlyMap<string, DeviceToken>,
  organizationId: OrganizationId,
  deviceId: string,
): Map<string, DeviceToken> {
  return withDeviceState(devices, organizationId, deviceId, 'revoked');
}

/**
 * Persist a provider rejection so the device stops receiving future pushes.
 *
 * @param devices tenant-keyed registry to update
 * @param organizationId active organization
 * @param deviceId organization-local device identifier
 * @returns a new registry with the device invalid when it is in this tenant
 */
export function withInvalidatedDevice(
  devices: ReadonlyMap<string, DeviceToken>,
  organizationId: OrganizationId,
  deviceId: string,
): Map<string, DeviceToken> {
  return withDeviceState(devices, organizationId, deviceId, 'invalid');
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
