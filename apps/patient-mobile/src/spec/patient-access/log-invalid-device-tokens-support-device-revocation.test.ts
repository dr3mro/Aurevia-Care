/**
 * PAT-01 clause 4 proof: a device token rejected by the provider is logged
 * without its token value and stops being eligible for delivery, and a
 * patient's revocation stops delivery only for that patient's own device.
 *
 * This is a test-only change. It drives the already-built appointments and
 * notifications domain through its module entry points, with plain synthetic
 * fixtures and injected boundaries (no jest mocks, no timers, no production
 * edits). Negative cases prove the tenant and patient boundary is never
 * crossed by revocation or invalidation.
 */
import {
  eligibleTokens,
  withInvalidatedDevice,
  withRevokedDevice,
} from '../../features/appointments/devices';
import { tenantKey } from '../../features/appointments/tenancy';
import type {
  DeviceToken,
  GenericNotice,
  InvalidTokenEvent,
} from '../../features/appointments/types';
import { dispatchNotice } from '../../features/notifications/delivery';
import { applyRegistration, commandForSession } from '../../features/notifications/registration';
import type { DeliveryDisposition } from '../../features/notifications/types';

/** Non-sensitive notice payload: the only kind this slice may emit. */
const notice: GenericNotice = {
  title: 'Appointment update',
  body: 'You have an update. Open the app to view details.',
};

/** One item already in the authenticated inbox; delivery never filters it. */
const inbox = [{ id: 'notice-1', detailRef: 'org-a\u0000apt-1' }];

/** Synthetic active registration bound to one patient in one organization. */
const activeToken = (id: string, patientId = 'patient-1'): DeviceToken => ({
  id,
  patientId,
  state: 'active',
  token: `synthetic-${id}`,
});

describe('PAT-01 clause 4 — invalid-token logging and device revocation', () => {
  it('logsInvalidTokenWithoutTheProviderTokenAndStopsEligibility', async () => {
    const key = tenantKey('org-a', 'dev-1');
    const devices = new Map<string, DeviceToken>([[key, activeToken('dev-1')]]);
    const sent: string[] = [];
    const logged: InvalidTokenEvent[] = [];

    const result = await dispatchNotice({
      devices,
      organizationId: 'org-a',
      patientId: 'patient-1',
      inbox,
      notice,
      gateway: {
        async send(value) {
          sent.push(value);
          const disposition: DeliveryDisposition = { kind: 'invalid', reason: 'provider_rejected' };
          return disposition;
        },
      },
      logger: {
        invalidToken(event) {
          logged.push(event);
        },
      },
      at: '2026-11-22T09:00:00Z',
    });

    expect(sent).toEqual(['synthetic-dev-1']);
    expect(logged).toEqual([
      {
        deviceId: 'dev-1',
        patientId: 'patient-1',
        reason: 'provider_rejected',
        at: '2026-11-22T09:00:00Z',
      },
    ]);
    expect(JSON.stringify(logged)).not.toContain('synthetic-dev-1');
    expect(result.devices.get(key)?.state).toBe('invalid');
    expect(eligibleTokens(result.devices, 'org-a', 'patient-1')).toEqual([]);
    expect(eligibleTokens(devices, 'org-a', 'patient-1')).toEqual([activeToken('dev-1')]);
    expect(result.inbox).toEqual(inbox);
  });

  it('signOutRevocationStopsDeliveryOnlyForTheOwnedDevice', async () => {
    const ownedKey = tenantKey('org-a', 'dev-1');
    const peerKey = tenantKey('org-a', 'dev-2');
    const peerDevice = activeToken('dev-2', 'patient-2');
    const source = new Map<string, DeviceToken>([
      [ownedKey, activeToken('dev-1')],
      [peerKey, peerDevice],
    ]);

    const command = commandForSession({
      kind: 'signed_out',
      organizationId: 'org-a',
      device: activeToken('dev-1'),
    });
    expect(command.kind).toBe('unregister');

    const revoked = applyRegistration(source, command);
    expect(revoked.get(ownedKey)?.state).toBe('revoked');
    expect(eligibleTokens(revoked, 'org-a', 'patient-1')).toEqual([]);
    expect(source.get(ownedKey)?.state).toBe('active');
    expect(revoked.get(peerKey)).toEqual(peerDevice);

    const sent: string[] = [];
    const logged: InvalidTokenEvent[] = [];
    const after = await dispatchNotice({
      devices: revoked,
      organizationId: 'org-a',
      patientId: 'patient-1',
      inbox,
      notice,
      gateway: {
        async send(value) {
          sent.push(value);
          return { kind: 'accepted' };
        },
      },
      logger: {
        invalidToken(event) {
          logged.push(event);
        },
      },
      at: '2026-11-22T09:30:00Z',
    });

    expect(sent).toEqual([]);
    expect(logged).toEqual([]);
    expect(after.inbox).toEqual(inbox);
  });

  it('refusesToRevokeOrInvalidateAPeerTenantOrAnotherPatientsDevice', () => {
    const peerTenantKey = tenantKey('org-b', 'dev-1');
    const otherPatientKey = tenantKey('org-a', 'dev-2');
    const peerTenantDevice = activeToken('dev-1', 'patient-1');
    const otherPatientDevice = activeToken('dev-2', 'patient-2');
    const source = new Map<string, DeviceToken>([
      [peerTenantKey, peerTenantDevice],
      [otherPatientKey, otherPatientDevice],
    ]);

    const revokedPeer = withRevokedDevice(source, 'org-a', 'dev-1', 'patient-1');
    const invalidatedOther = withInvalidatedDevice(source, 'org-a', 'dev-2', 'patient-1');
    const invalidatedMissing = withInvalidatedDevice(source, 'org-a', 'missing', 'patient-1');

    expect(revokedPeer).toEqual(source);
    expect(invalidatedOther).toEqual(source);
    expect(invalidatedMissing).toEqual(source);
    expect(revokedPeer.get(peerTenantKey)?.state).toBe('active');
    expect(invalidatedOther.get(otherPatientKey)?.state).toBe('active');
  });
});
