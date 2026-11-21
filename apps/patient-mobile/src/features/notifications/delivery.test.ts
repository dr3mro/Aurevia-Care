import { eligibleTokens } from '../appointments/devices';
import { tenantKey } from '../appointments/tenancy';
import type { DeviceToken, GenericNotice, InvalidTokenEvent } from '../appointments/types';
import type { DeliveryDisposition } from './types';
import { dispatchNotice } from './delivery';

/** PAT-01, ARC-07: invalid tokens are dropped and failed pushes never empty the inbox. */
const notice: GenericNotice = {
  title: 'Appointment update',
  body: 'You have an update. Open the app to view details.',
};

const token = (id: string, patientId = 'patient-1'): DeviceToken => ({
  id,
  patientId,
  state: 'active',
  token: `synthetic-${id}`,
});

const inbox = [{ id: 'notice-1', detailRef: 'org-a\u0000apt-1' }];

describe('dispatchNotice', () => {
  it('drops and logs an invalid token without ever logging the token value', async () => {
    const devices = new Map<string, DeviceToken>([[tenantKey('org-a', 'dev-1'), token('dev-1')]]);
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
      at: '2026-11-21T08:00:00Z',
    });

    expect(sent).toEqual(['synthetic-dev-1']);
    expect(result.devices.get(tenantKey('org-a', 'dev-1'))?.state).toBe('invalid');
    expect(eligibleTokens(result.devices, 'org-a', 'patient-1')).toEqual([]);
    expect(eligibleTokens(devices, 'org-a', 'patient-1')).toEqual([token('dev-1')]);
    expect(logged).toEqual([
      {
        deviceId: 'dev-1',
        patientId: 'patient-1',
        reason: 'provider_rejected',
        at: '2026-11-21T08:00:00Z',
      },
    ]);
    expect(JSON.stringify(logged)).not.toContain('synthetic-dev-1');
    expect(result.outcomes).toEqual([
      { deviceId: 'dev-1', disposition: { kind: 'invalid', reason: 'provider_rejected' } },
    ]);
    expect(result.inbox).toEqual(inbox);
  });

  it('keeps an accepted token active and logs nothing', async () => {
    const devices = new Map<string, DeviceToken>([[tenantKey('org-a', 'dev-1'), token('dev-1')]]);
    const logged: InvalidTokenEvent[] = [];
    const result = await dispatchNotice({
      devices,
      organizationId: 'org-a',
      patientId: 'patient-1',
      inbox,
      notice,
      gateway: {
        async send() {
          return { kind: 'accepted' };
        },
      },
      logger: {
        invalidToken(event) {
          logged.push(event);
        },
      },
      at: '2026-11-21T08:00:00Z',
    });

    expect(logged).toEqual([]);
    expect(result.devices.get(tenantKey('org-a', 'dev-1'))?.state).toBe('active');
    expect(result.outcomes).toEqual([{ deviceId: 'dev-1', disposition: { kind: 'accepted' } }]);
    expect(result.inbox).toEqual(inbox);
  });

  it('keeps the inbox item when there is no eligible device to notify', async () => {
    const sent: string[] = [];
    const result = await dispatchNotice({
      devices: new Map<string, DeviceToken>(),
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
        invalidToken() {
          throw new Error('unexpected invalid-token log');
        },
      },
      at: '2026-11-21T08:00:00Z',
    });

    expect(sent).toEqual([]);
    expect(result.outcomes).toEqual([]);
    expect(result.inbox).toEqual(inbox);
  });

  it('does not notify a peer tenant or another patient device', async () => {
    const devices = new Map<string, DeviceToken>([
      [tenantKey('org-b', 'dev-1'), token('dev-1')],
      [tenantKey('org-a', 'dev-2'), token('dev-2', 'patient-2')],
      [tenantKey('org-a', 'dev-3'), token('dev-3')],
    ]);
    const sent: string[] = [];
    const result = await dispatchNotice({
      devices,
      organizationId: 'org-a',
      patientId: 'patient-1',
      inbox,
      notice,
      gateway: {
        async send(value) {
          sent.push(value);
          return { kind: 'invalid', reason: 'invalid_token' };
        },
      },
      logger: {
        invalidToken() {
          // Collected through the resulting device state below.
        },
      },
      at: '2026-11-21T08:00:00Z',
    });

    expect(sent).toEqual(['synthetic-dev-3']);
    expect(result.devices.get(tenantKey('org-b', 'dev-1'))?.state).toBe('active');
    expect(result.devices.get(tenantKey('org-a', 'dev-2'))?.state).toBe('active');
    expect(result.devices.get(tenantKey('org-a', 'dev-3'))?.state).toBe('invalid');
    expect(result.inbox).toEqual(inbox);
  });
});
