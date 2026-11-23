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
 * Index a flat device projection under one organization's tenant keys.
 *
 * @param devices flat registrations supplied by the signed-in host
 * @param organizationId active organization that owns the keys
 * @returns a tenant-keyed registry for the domain functions in this module
 */
function toRegistry(
  devices: readonly DeviceToken[],
  organizationId: OrganizationId,
): Map<string, DeviceToken> {
  const registry = new Map<string, DeviceToken>();
  for (const token of devices) {
    registry.set(tenantKey(organizationId, token.id), token);
  }
  return registry;
}

/**
 * Load the devices this patient may act on in the active organization.
 *
 * A flat host projection is indexed under the active tenant first, so a row
 * with the same local id in another organization is never selected. Only active
 * registrations owned by the verified patient are offered for display.
 *
 * @param devices flat registrations supplied by the signed-in host
 * @param organizationId active organization from the account
 * @param patientId verified linked patient
 * @returns active, tenant-owned devices of that patient
 */
export function devicesForPatient(
  devices: readonly DeviceToken[],
  organizationId: OrganizationId,
  patientId: string,
): DeviceToken[] {
  return eligibleTokens(toRegistry(devices, organizationId), organizationId, patientId);
}

/**
 * Re-resolve a pressed device id against the devices the patient may act on.
 *
 * The page receives a bare local id from a control. Resolving it again here
 * means a foreign-tenant, foreign-patient, revoked or guessed identifier can
 * never become a confirmation target.
 *
 * @param devices flat registrations read by the signed-in page
 * @param organizationId active organization from the account
 * @param deviceId organization-local identifier from the pressed control
 * @param patientId verified linked patient
 * @returns the active device when it belongs to this patient and tenant
 */
export function findEligibleDevice(
  devices: readonly DeviceToken[],
  organizationId: OrganizationId,
  deviceId: string,
  patientId: string,
): DeviceToken | undefined {
  return devicesForPatient(devices, organizationId, patientId).find(
    (token) => token.id === deviceId,
  );
}

/**
 * Recompute the devices offered after one revocation settles.
 *
 * No server device-list route exists in V1, so a successful DELETE is applied
 * to the tenant-scoped local projection and eligibility is recomputed. The
 * revoked device disappears while another patient's or another tenant's row is
 * never selected; the source list is not mutated.
 *
 * @param devices flat registrations read before the command
 * @param organizationId active organization from the account
 * @param deviceId identifier the server just revoked
 * @param patientId verified linked patient
 * @returns active devices still eligible for this patient in this tenant
 */
export function refreshVisibleDevices(
  devices: readonly DeviceToken[],
  organizationId: OrganizationId,
  deviceId: string,
  patientId: string,
): DeviceToken[] {
  const registry = toRegistry(devices, organizationId);
  return eligibleTokens(
    withRevokedDevice(registry, organizationId, deviceId, patientId),
    organizationId,
    patientId,
  );
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
 * The verified patient is part of the lookup, not just the organization: a
 * valid local device id owned by another patient in the same tenant is left
 * untouched, so one patient can never revoke or invalidate another's device.
 *
 * @param devices tenant-keyed registry to update
 * @param organizationId active organization
 * @param deviceId organization-local device identifier
 * @param patientId verified patient that owns the device
 * @param state new delivery state
 * @returns a new registry containing the updated registration
 */
function withDeviceState(
  devices: ReadonlyMap<string, DeviceToken>,
  organizationId: OrganizationId,
  deviceId: string,
  patientId: string,
  state: DeviceState,
): Map<string, DeviceToken> {
  const key = tenantKey(organizationId, deviceId);
  const current = devices.get(key);
  if (!current || current.patientId !== patientId) {
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
 * @param patientId verified patient that owns the device
 * @returns a new registry with the device revoked when it belongs to this patient and tenant
 */
export function withRevokedDevice(
  devices: ReadonlyMap<string, DeviceToken>,
  organizationId: OrganizationId,
  deviceId: string,
  patientId: string,
): Map<string, DeviceToken> {
  return withDeviceState(devices, organizationId, deviceId, patientId, 'revoked');
}

/**
 * Persist a provider rejection so the device stops receiving future pushes.
 *
 * @param devices tenant-keyed registry to update
 * @param organizationId active organization
 * @param deviceId organization-local device identifier
 * @param patientId verified patient that owns the device
 * @returns a new registry with the device invalid when it belongs to this patient and tenant
 */
export function withInvalidatedDevice(
  devices: ReadonlyMap<string, DeviceToken>,
  organizationId: OrganizationId,
  deviceId: string,
  patientId: string,
): Map<string, DeviceToken> {
  return withDeviceState(devices, organizationId, deviceId, patientId, 'invalid');
}

/**
 * Store a device under its tenant key for the active organization only.
 *
 * A device id already owned by a different patient in this organization is
 * never rebound: the displaced patient would otherwise lose the ability to
 * revoke or invalidate their own registration (PAT-01, IAM-05).
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
  const key = tenantKey(organizationId, token.id);
  const current = devices.get(key);
  if (current && current.patientId !== token.patientId) {
    return new Map(devices);
  }
  const next = new Map(devices);
  next.set(key, token);
  return next;
}
