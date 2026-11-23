/**
 * PAT-01 clause 2: an appointment status change prefers a push notice whose
 * text is generic and carries no clinical detail. These tests exercise the
 * built generic-text, authentication-gated detail and tenant-scoped delivery
 * helpers. The unbuilt app-level status-change to push trigger is recorded as
 * a gap in the companion clause document; no production code is added here.
 */
import { GENERIC_NOTICE, buildGenericNotice } from '../../features/appointments/notifications';
import { tenantKey } from '../../features/appointments/tenancy';
import type { AuthState, DeviceToken, GenericNotice } from '../../features/appointments/types';
import { dispatchNotice } from '../../features/notifications/delivery';
import { buildPushPayload, notificationContent } from '../../features/notifications/payload';
import type { PushGateway } from '../../features/notifications/types';

/** Opaque tenant-scoped detail reference; NUL cannot occur in a generated local id. */
const detailRef = 'org-a\u0000apt-1';

/** Synthetic cancelled appointment detail; contains no real patient data. */
const appointmentDetail = {
  id: 'apt-1',
  patientName: 'Layla Synthetic',
  facilityCode: 'MAIN',
  status: 'cancelled',
  result: 'Hemoglobin 7.2',
  startsAt: '2026-12-01T07:00:00Z',
};

/** The approved text as literals, so a change to the shared template cannot slip through. */
const approvedNotice = {
  title: 'Appointment update',
  body: 'You have an update about your appointment. Open the app to view details.',
};

/** Values that must never reach push text for a status change. */
const clinicalFields = [
  appointmentDetail.patientName,
  appointmentDetail.facilityCode,
  appointmentDetail.status,
  appointmentDetail.result,
  appointmentDetail.startsAt,
  appointmentDetail.id,
];

/** Build a synthetic active device for the tenant-keyed registry. */
const token = (id: string, patientId = 'patient-1'): DeviceToken => ({
  id,
  patientId,
  state: 'active',
  token: `synthetic-${id}`,
});

/** Authenticated inbox item that delivery must never filter or empty. */
const inbox = [{ id: 'notice-1', detailRef }];

/** Record exactly which tokens and notices a gateway received, with no clinical logging. */
function noticeSpy(): { gateway: PushGateway; tokens: string[]; notices: GenericNotice[] } {
  const tokens: string[] = [];
  const notices: GenericNotice[] = [];
  return {
    tokens,
    notices,
    gateway: {
      async send(value, notice) {
        tokens.push(value);
        notices.push(notice);
        return { kind: 'accepted' };
      },
    },
  };
}

describe('prefer push notifications for status changes with generic text', () => {
  it('uses only generic text when an appointment status changes', async () => {
    const payload = buildPushPayload(detailRef);
    const noticeText = JSON.stringify(payload.notice);

    expect(payload.notice).toEqual(approvedNotice);
    expect(buildGenericNotice()).toEqual(approvedNotice);
    expect(payload.notice.title).toBe(approvedNotice.title);
    expect(payload.notice.body).toBe(approvedNotice.body);
    for (const field of clinicalFields) {
      expect(noticeText).not.toContain(field);
    }
    expect(payload.detailRef).toBe(detailRef);

    // After sign-in the detail is available, but the notice must still be detail-free.
    const fetched = await notificationContent({ authenticated: true }, detailRef, async () => appointmentDetail);
    expect(fetched.detail).toEqual({ kind: 'detail', value: appointmentDetail });
    expect(fetched.payload.notice).toEqual(approvedNotice);
    for (const field of clinicalFields) {
      expect(JSON.stringify(fetched.payload.notice)).not.toContain(field);
    }
  });

  it('withholds clinical detail and keeps the notice generic before sign-in', async () => {
    const calls: string[] = [];
    const auth: AuthState = { authenticated: false };

    const result = await notificationContent(auth, detailRef, async (ref) => {
      calls.push(ref);
      return appointmentDetail;
    });

    expect(calls).toEqual([]);
    expect(result.detail).toEqual({ kind: 'auth_required' });
    expect(result.payload.notice).toEqual(approvedNotice);
    const noticeText = JSON.stringify(result.payload.notice);
    for (const field of clinicalFields) {
      expect(noticeText).not.toContain(field);
    }
    expect(result.payload.detailRef).toBe(detailRef);
  });

  it('refuses to deliver a status notice to another tenant', async () => {
    const devices = new Map<string, DeviceToken>([
      [tenantKey('org-b', 'dev-1'), token('dev-1')],
      [tenantKey('org-a', 'dev-2'), token('dev-2', 'patient-2')],
    ]);
    const spy = noticeSpy();
    const notice: GenericNotice = GENERIC_NOTICE;
    const dispatch = {
      organizationId: 'org-a',
      patientId: 'patient-1',
      inbox,
      notice,
      gateway: spy.gateway,
      logger: {
        invalidToken() {
          throw new Error('unexpected invalid-token log');
        },
      },
      at: '2026-11-22T09:00:00Z',
    };

    const result = await dispatchNotice({ devices, ...dispatch });
    expect(spy.tokens).toEqual([]);
    expect(spy.notices).toEqual([]);
    expect(result.outcomes).toEqual([]);
    expect(result.inbox).toEqual(inbox);
    expect(result.devices).toEqual(devices);

    // Positive control: the same boundary does deliver the generic notice to the verified patient.
    const ownDevices = new Map<string, DeviceToken>([...devices, [tenantKey('org-a', 'dev-0'), token('dev-0')]]);
    const delivered = await dispatchNotice({ devices: ownDevices, ...dispatch });
    expect(spy.tokens).toEqual(['synthetic-dev-0']);
    expect(spy.notices).toEqual([approvedNotice]);
    expect(delivered.outcomes).toEqual([{ deviceId: 'dev-0', disposition: { kind: 'accepted' } }]);
    expect(delivered.inbox).toEqual(inbox);
  });
});
