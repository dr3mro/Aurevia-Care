import { withRegisteredDevice, withRevokedDevice } from '../appointments/devices';
import type { DeviceToken } from '../appointments/types';
import type { RegistrationCommand, SessionEvent } from './types';

/**
 * Translate a verified session transition into one registration side effect.
 *
 * Sign-in registers the device for push; sign-out unregisters it so no further
 * push can be delivered to a signed-out patient (PAT-01).
 *
 * @param event verified session transition
 * @returns the command the app must apply
 */
export function commandForSession(event: SessionEvent): RegistrationCommand {
  if (event.kind === 'signed_in') {
    return { kind: 'register', organizationId: event.organizationId, device: event.device };
  }
  return { kind: 'unregister', organizationId: event.organizationId, device: event.device };
}

/**
 * Apply a registration command to the tenant-keyed device registry.
 *
 * Both branches reuse the appointments domain's tenant- and patient-scoped
 * helpers, so a command can never register or revoke another patient's device
 * or a device in another organization.
 *
 * @param devices tenant-keyed registry to update
 * @param command registration side effect to apply
 * @returns a new registry with the device registered or unregistered
 */
export function applyRegistration(
  devices: ReadonlyMap<string, DeviceToken>,
  command: RegistrationCommand,
): Map<string, DeviceToken> {
  if (command.kind === 'register') {
    return withRegisteredDevice(devices, command.organizationId, command.device);
  }
  return withRevokedDevice(
    devices,
    command.organizationId,
    command.device.id,
    command.device.patientId,
  );
}
