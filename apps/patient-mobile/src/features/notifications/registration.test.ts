import { eligibleTokens } from '../appointments/devices';
import { tenantKey } from '../appointments/tenancy';
import type { DeviceToken, SessionEvent } from './types';
import { applyRegistration, commandForSession } from './registration';

/** PAT-01: register after sign-in and unregister at sign-out. */
const device: DeviceToken = {
  id: 'dev-1',
  patientId: 'patient-1',
  state: 'active',
  token: 'synthetic-token',
};

describe('commandForSession', () => {
  it('commands registration for a verified sign-in', () => {
    const event: SessionEvent = {
      kind: 'signed_in',
      organizationId: 'org-a',
      device,
    };
    expect(commandForSession(event)).toEqual({
      kind: 'register',
      organizationId: 'org-a',
      device,
    });
  });

  it('commands unregistration for a sign-out', () => {
    const event: SessionEvent = {
      kind: 'signed_out',
      organizationId: 'org-a',
      device,
    };
    expect(commandForSession(event)).toEqual({
      kind: 'unregister',
      organizationId: 'org-a',
      device,
    });
  });
});

describe('applyRegistration', () => {
  it('registers a device under the active tenant key so it becomes eligible', () => {
    const command = commandForSession({
      kind: 'signed_in',
      organizationId: 'org-a',
      device,
    });
    const source = new Map<string, DeviceToken>();
    const next = applyRegistration(source, command);

    expect(next.get(tenantKey('org-a', 'dev-1'))).toEqual(device);
    expect(eligibleTokens(next, 'org-a', 'patient-1')).toEqual([device]);
    expect(source.size).toBe(0);
  });

  it('unregisters the device at sign-out so it stops receiving pushes', () => {
    const source = new Map<string, DeviceToken>([[tenantKey('org-a', 'dev-1'), device]]);
    const command = commandForSession({
      kind: 'signed_out',
      organizationId: 'org-a',
      device,
    });
    const next = applyRegistration(source, command);

    expect(next.get(tenantKey('org-a', 'dev-1'))?.state).toBe('revoked');
    expect(eligibleTokens(next, 'org-a', 'patient-1')).toEqual([]);
    expect(source.get(tenantKey('org-a', 'dev-1'))).toEqual(device);
  });

  it('does not unregister a peer tenant or another patient device', () => {
    const foreign = { ...device, id: 'dev-2', patientId: 'patient-2' };
    const source = new Map<string, DeviceToken>([
      [tenantKey('org-b', 'dev-1'), device],
      [tenantKey('org-a', 'dev-2'), foreign],
    ]);
    const command = commandForSession({
      kind: 'signed_out',
      organizationId: 'org-a',
      device,
    });
    const next = applyRegistration(source, command);

    expect(next.get(tenantKey('org-b', 'dev-1'))).toEqual(device);
    expect(next.get(tenantKey('org-a', 'dev-2'))).toEqual(foreign);
  });
});
