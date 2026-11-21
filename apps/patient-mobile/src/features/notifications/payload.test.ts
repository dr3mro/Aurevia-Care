import type { AuthState } from '../appointments/types';
import { buildPushPayload, notificationContent } from './payload';

/** PAT-01: push text is generic and clinical content is loaded only after sign-in. */
const detailRef = 'org-a\u0000apt-1';
const appointmentDetail = {
  id: 'apt-1',
  patientName: 'Layla Synthetic',
  facilityCode: 'MAIN',
  status: 'cancelled',
  result: 'Hemoglobin 7.2',
  startsAt: '2026-12-01T07:00:00Z',
};

const clinicalFields = [
  appointmentDetail.patientName,
  appointmentDetail.facilityCode,
  appointmentDetail.status,
  appointmentDetail.result,
  appointmentDetail.startsAt,
  appointmentDetail.id,
];

describe('buildPushPayload', () => {
  it('uses fixed generic text with no clinical detail', () => {
    const payload = buildPushPayload(detailRef);
    const noticeText = JSON.stringify(payload.notice);
    for (const field of clinicalFields) {
      expect(noticeText).not.toContain(field);
    }
    expect(noticeText).toContain('Open the app');
    expect(payload.detailRef).toBe(detailRef);
  });
});

describe('notificationContent', () => {
  it('returns generic text and requires authentication before any detail fetch', async () => {
    const calls: string[] = [];
    const auth: AuthState = { authenticated: false };
    const result = await notificationContent(auth, detailRef, async (ref) => {
      calls.push(ref);
      return appointmentDetail;
    });

    expect(calls).toEqual([]);
    expect(result.detail).toEqual({ kind: 'auth_required' });
    const noticeText = JSON.stringify(result.payload.notice);
    for (const field of clinicalFields) {
      expect(noticeText).not.toContain(field);
    }
  });

  it('loads the detail only after authentication', async () => {
    const calls: string[] = [];
    const auth: AuthState = { authenticated: true };
    const result = await notificationContent(auth, detailRef, async (ref) => {
      calls.push(ref);
      return appointmentDetail;
    });

    expect(calls).toEqual([detailRef]);
    expect(result.detail).toEqual({ kind: 'detail', value: appointmentDetail });
    const noticeText = JSON.stringify(result.payload.notice);
    for (const field of clinicalFields) {
      expect(noticeText).not.toContain(field);
    }
  });
});
