/**
 * PAT-01: an appointment status change produces at most one generic push.
 *
 * All boundaries are synthetic: the gateway records what it was asked to send,
 * the detail fetcher is an injected function and the clock is a fixed literal.
 * No clinical value is used as a fixture outside the detail object itself, and
 * the tests assert that none of it reaches a push, an outcome or a log event.
 */
import { TransportError } from '../../api/transport';
import { tenantKey } from '../appointments/tenancy';
import type {
  AuthState,
  DeviceToken,
  GenericNotice,
  InvalidTokenEvent,
} from '../appointments/types';
import type { DeliveryDisposition, InboxItem, PushGateway } from './types';
import { statusNoticeKey, triggerStatusNotice } from './statusChangeTrigger';
import type {
  AppointmentStatusChange,
  StatusChangeDependencies,
} from './statusChangeTrigger';

const ORGANIZATION = 'org-a';
const PATIENT = 'patient-1';
const DETAIL_REF = 'org-a\u0000apt-1';
const AT = '2026-11-27T09:00:00Z';

/** Approved text as literals so a change to the shared template is caught. */
const approvedNotice: GenericNotice = {
  title: 'Appointment update',
  body: 'You have an update about your appointment. Open the app to view details.',
};

/** Synthetic cancelled appointment; no real patient or clinical narrative. */
const appointmentDetail = {
  id: 'apt-1',
  patientName: 'Layla Synthetic',
  facilityCode: 'MAIN',
  status: 'cancelled',
  result: 'Hemoglobin 7.2',
  startsAt: '2026-12-01T07:00:00Z',
};

/** Values that must never reach a push notice, an outcome or a log event. */
const clinicalFields = [
  appointmentDetail.patientName,
  appointmentDetail.facilityCode,
  appointmentDetail.status,
  appointmentDetail.result,
  appointmentDetail.startsAt,
  appointmentDetail.id,
];

const inbox: readonly InboxItem[] = [{ id: 'notice-1', detailRef: DETAIL_REF }];

/** Synthetic active registration for the verified patient. */
function token(id: string, patientId = PATIENT): DeviceToken {
  return { id, patientId, state: 'active', token: `synthetic-${id}` };
}

/** Record gateway calls without any provider or clinical logging. */
function noticeSpy(disposition: DeliveryDisposition): {
  gateway: PushGateway;
  tokens: string[];
  notices: GenericNotice[];
} {
  const tokens: string[] = [];
  const notices: GenericNotice[] = [];
  return {
    tokens,
    notices,
    gateway: {
      async send(value, notice) {
        tokens.push(value);
        notices.push(notice);
        return disposition;
      },
    },
  };
}

interface HarnessOptions {
  readonly auth?: AuthState;
  readonly devices?: ReadonlyMap<string, DeviceToken>;
  readonly fetch?: (ref: string) => Promise<typeof appointmentDetail>;
  readonly disposition?: DeliveryDisposition;
}

/** Build injected dependencies plus the spies that observe them. */
function harness(options: HarnessOptions = {}) {
  const spy = noticeSpy(options.disposition ?? { kind: 'accepted' });
  const logged: InvalidTokenEvent[] = [];
  const fetchCalls: string[] = [];
  const dispatched = new Set<string>();
  let expired = 0;
  const deps: StatusChangeDependencies<typeof appointmentDetail> = {
    organizationId: ORGANIZATION,
    patientId: PATIENT,
    auth: options.auth ?? { authenticated: true },
    devices: options.devices ?? new Map([[tenantKey(ORGANIZATION, 'dev-1'), token('dev-1')]]),
    inbox,
    gateway: spy.gateway,
    logger: {
      invalidToken(event) {
        logged.push(event);
      },
    },
    at: AT,
    fetchDetail:
      options.fetch ??
      (async (ref) => {
        fetchCalls.push(ref);
        return appointmentDetail;
      }),
    dispatched,
    onSessionExpired() {
      expired += 1;
    },
  };
  return {
    deps,
    spy,
    logged,
    fetchCalls,
    dispatched,
    devices: deps.devices,
    expiredCount: () => expired,
  };
}

/** Build one synthetic status-change event; overrides exercise the trust boundary. */
function change(overrides: Partial<AppointmentStatusChange> = {}): AppointmentStatusChange {
  return {
    organizationId: ORGANIZATION,
    patientId: PATIENT,
    appointmentId: 'apt-1',
    status: 'cancelled',
    detailRef: DETAIL_REF,
    ...overrides,
  };
}

/** Assert that no clinical value escaped into any observable side effect. */
function expectNoClinicalEgress(egress: unknown): void {
  const text = JSON.stringify(egress);
  for (const field of clinicalFields) {
    expect(text).not.toContain(field);
  }
}

describe('triggerStatusNotice', () => {
  it('sends one content-free notice and fetches detail only after sign-in', async () => {
    const h = harness();
    const result = await triggerStatusNotice(change(), h.deps);

    expect(h.spy.tokens).toEqual(['synthetic-dev-1']);
    expect(h.spy.notices).toEqual([approvedNotice]);
    expect(h.fetchCalls).toEqual([DETAIL_REF]);
    expect(result.kind).toBe('dispatched');
    expect(result.notice).toEqual(approvedNotice);
    expect(result.detail).toEqual({ kind: 'detail', value: appointmentDetail });
    expect(result.outcomes).toEqual([{ deviceId: 'dev-1', disposition: { kind: 'accepted' } }]);
    expect(result.inbox).toEqual(inbox);
    expect(h.dispatched.has(statusNoticeKey(ORGANIZATION, PATIENT, 'apt-1', 'cancelled'))).toBe(true);
    expectNoClinicalEgress({ notices: h.spy.notices, outcomes: result.outcomes, logged: h.logged });
  });

  it('neither fetches nor pushes while signed out, and leaves the key reusable', async () => {
    const h = harness({ auth: { authenticated: false } });
    const result = await triggerStatusNotice(change(), h.deps);

    expect(result.kind).toBe('auth_required');
    expect(result.detail).toEqual({ kind: 'auth_required' });
    expect(h.fetchCalls).toEqual([]);
    expect(h.spy.tokens).toEqual([]);
    expect(h.dispatched.size).toBe(0);
    expect(h.expiredCount()).toBe(0);

    const retry = await triggerStatusNotice(change(), { ...h.deps, auth: { authenticated: true } });
    expect(retry.kind).toBe('dispatched');
    expect(h.spy.tokens).toEqual(['synthetic-dev-1']);
  });

  it('drops a retry of the same tenant, patient, appointment and status', async () => {
    const h = harness();
    const first = await triggerStatusNotice(change(), h.deps);
    const second = await triggerStatusNotice(change(), h.deps);

    expect(first.kind).toBe('dispatched');
    expect(second.kind).toBe('duplicate');
    expect(second.detail).toEqual({ kind: 'none' });
    expect(second.outcomes).toEqual([]);
    expect(second.inbox).toEqual(inbox);
    expect(h.spy.tokens).toEqual(['synthetic-dev-1']);
    expect(h.fetchCalls).toEqual([DETAIL_REF]);
  });

  it('blocks two emits in one turn before the first dispatch resolves', async () => {
    const h = harness();
    const [first, second] = await Promise.all([
      triggerStatusNotice(change(), h.deps),
      triggerStatusNotice(change(), h.deps),
    ]);

    expect([first.kind, second.kind].sort()).toEqual(['dispatched', 'duplicate']);
    expect(h.spy.tokens).toEqual(['synthetic-dev-1']);
  });

  it('treats a new status or a new appointment as a new event', async () => {
    const h = harness();
    await triggerStatusNotice(change(), h.deps);
    await triggerStatusNotice(change({ status: 'booked' }), h.deps);
    await triggerStatusNotice(change({ appointmentId: 'apt-2' }), h.deps);

    expect(h.spy.tokens).toEqual(['synthetic-dev-1', 'synthetic-dev-1', 'synthetic-dev-1']);
    expect(h.dispatched.size).toBe(3);
  });

  it('keeps tenant, patient, appointment and status keys distinct', async () => {
    const h = harness();
    await triggerStatusNotice(change({ appointmentId: 'a', status: 'booked' }), h.deps);
    await triggerStatusNotice(change({ appointmentId: 'ab', status: 'booked' }), h.deps);
    await triggerStatusNotice(change({ appointmentId: 'a', status: 'cancelled' }), h.deps);

    expect(h.spy.tokens).toEqual(['synthetic-dev-1', 'synthetic-dev-1', 'synthetic-dev-1']);
    expect(h.dispatched.size).toBe(3);
    // A plain concatenation would collapse these two different tuples.
    expect(statusNoticeKey(ORGANIZATION, PATIENT, 'a', 'bc')).not.toBe(
      statusNoticeKey(ORGANIZATION, PATIENT, 'ab', 'c'),
    );
  });

  it('treats the same appointment and status in another verified tenant as a new event', async () => {
    const h = harness();
    await triggerStatusNotice(change(), h.deps);
    const peerDevices = new Map([[tenantKey('org-b', 'dev-b'), token('dev-b')]]);
    const peer = harness({
      devices: peerDevices,
      fetch: h.deps.fetchDetail,
    });
    const peerDeps: StatusChangeDependencies<typeof appointmentDetail> = {
      ...peer.deps,
      organizationId: 'org-b',
    };
    const result = await triggerStatusNotice(change({ organizationId: 'org-b' }), peerDeps);

    expect(result.kind).toBe('dispatched');
    expect(peer.spy.tokens).toEqual(['synthetic-dev-b']);
    expect(h.spy.tokens).toEqual(['synthetic-dev-1']);
  });

  it('ends the session and sends no push on a 401 detail fetch, then allows a retry', async () => {
    const h = harness({
      fetch: async () => {
        throw new TransportError('unauthenticated', { code: 'SESSION_EXPIRED', correlationId: 'req-1' });
      },
    });
    const result = await triggerStatusNotice(change(), h.deps);

    expect(result.kind).toBe('session_expired');
    expect(result.detail).toEqual({ kind: 'none' });
    expect(result.outcomes).toEqual([]);
    expect(h.expiredCount()).toBe(1);
    expect(h.spy.tokens).toEqual([]);
    expect(h.logged).toEqual([]);
    expect(h.dispatched.size).toBe(0);
    expect(JSON.stringify(h.logged)).not.toContain('SESSION_EXPIRED');
    expect(JSON.stringify(h.logged)).not.toContain('req-1');

    const retry = await triggerStatusNotice(change(), harness().deps);
    expect(retry.kind).toBe('dispatched');
  });

  it('sends no push on another fetch failure and keeps the key reusable', async () => {
    const h = harness({
      fetch: async () => {
        throw new Error('offline');
      },
    });
    const result = await triggerStatusNotice(change(), h.deps);

    expect(result.kind).toBe('fetch_failed');
    expect(h.spy.tokens).toEqual([]);
    expect(h.expiredCount()).toBe(0);
    expect(h.dispatched.size).toBe(0);

    const retry = await triggerStatusNotice(change(), harness().deps);
    expect(retry.kind).toBe('dispatched');
    expect(retry.outcomes).toEqual([{ deviceId: 'dev-1', disposition: { kind: 'accepted' } }]);
  });

  it('logs an invalid token without the provider value and keeps the inbox', async () => {
    const h = harness({ disposition: { kind: 'invalid', reason: 'provider_rejected' } });
    const result = await triggerStatusNotice(change(), h.deps);

    expect(result.kind).toBe('dispatched');
    expect(h.logged).toEqual([
      { deviceId: 'dev-1', patientId: PATIENT, reason: 'provider_rejected', at: AT },
    ]);
    expect(JSON.stringify(h.logged)).not.toContain('synthetic-dev-1');
    // Invalidation is returned as a new registry; the source is never mutated.
    expect(h.devices.get(tenantKey(ORGANIZATION, 'dev-1'))?.state).toBe('active');
    expect(result.inbox).toEqual(inbox);
    expect(result.outcomes).toEqual([
      { deviceId: 'dev-1', disposition: { kind: 'invalid', reason: 'provider_rejected' } },
    ]);
    expectNoClinicalEgress({ notices: h.spy.notices, outcomes: result.outcomes, logged: h.logged });
  });

  it('rejects an event for another tenant or another patient without any side effect', async () => {
    const h = harness();
    const peerTenant = await triggerStatusNotice(change({ organizationId: 'org-b' }), h.deps);
    const peerPatient = await triggerStatusNotice(change({ patientId: 'patient-2' }), h.deps);

    expect(peerTenant.kind).toBe('rejected');
    expect(peerPatient.kind).toBe('rejected');
    expect(peerTenant.detail).toEqual({ kind: 'none' });
    expect(peerPatient.outcomes).toEqual([]);
    expect(peerTenant.inbox).toEqual(inbox);
    expect(h.spy.tokens).toEqual([]);
    expect(h.fetchCalls).toEqual([]);
    expect(h.logged).toEqual([]);
    expect(h.devices.get(tenantKey(ORGANIZATION, 'dev-1'))?.state).toBe('active');
    expect(h.dispatched.size).toBe(0);
  });

  it('never sends to a peer-tenant or another patient device', async () => {
    const devices = new Map<string, DeviceToken>([
      [tenantKey('org-b', 'dev-1'), token('dev-1')],
      [tenantKey(ORGANIZATION, 'dev-2'), token('dev-2', 'patient-2')],
    ]);
    const h = harness({ devices });
    const result = await triggerStatusNotice(change(), h.deps);

    expect(h.spy.tokens).toEqual([]);
    expect(result.kind).toBe('dispatched');
    expect(result.outcomes).toEqual([]);
    expect(result.inbox).toEqual(inbox);
    expect(devices.get(tenantKey('org-b', 'dev-1'))?.state).toBe('active');
    expect(devices.get(tenantKey(ORGANIZATION, 'dev-2'))?.state).toBe('active');
  });

  it('keeps the inbox when the patient has no eligible device', async () => {
    const h = harness({ devices: new Map<string, DeviceToken>() });
    const result = await triggerStatusNotice(change(), h.deps);

    expect(h.spy.tokens).toEqual([]);
    expect(result.kind).toBe('dispatched');
    expect(result.outcomes).toEqual([]);
    expect(result.inbox).toEqual(inbox);
  });
});
