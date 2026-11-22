import type { Appointment } from './types';
import { buildGenericNotice, detailAfterAuth, GENERIC_NOTICE } from './notifications';

/** S-G3-01: generic push text and detail retrieval only after authentication (PAT-01, OPS-02). */
const appointment: Appointment = {
  id: 'apt-1',
  version: 3,
  patientId: 'patient-1',
  facilityCode: 'MAIN',
  facilityTimeZone: 'Asia/Riyadh',
  startsAt: '2026-12-01T07:00:00Z',
  status: 'cancelled',
  confirmationEnabled: true,
  confirmedAt: null,
};

describe('buildGenericNotice', () => {
  it('returns the fixed generic template for every status', () => {
    const notice = buildGenericNotice();
    expect(notice).toEqual(GENERIC_NOTICE);
    for (const status of ['booked', 'checked_in', 'completed', 'cancelled', 'no_show'] as const) {
      expect(buildGenericNotice()).toEqual(notice);
      expect(JSON.stringify(notice)).not.toContain(status);
    }
    expect(JSON.stringify(notice)).not.toContain('MAIN');
    expect(JSON.stringify(notice)).not.toContain('Asia/Riyadh');
    expect(JSON.stringify(notice)).not.toContain('patient-1');
    expect(JSON.stringify(notice)).not.toContain(appointment.startsAt);
  });
});

describe('detailAfterAuth', () => {
  function fetcherSpy(result: Appointment): {
    fn: (ref: string) => Promise<Appointment>;
    calls: string[];
  } {
    const calls: string[] = [];
    return {
      fn: async (ref: string) => {
        calls.push(ref);
        return result;
      },
      calls,
    };
  }

  it('does not call the detail fetcher before authentication', async () => {
    const fetcher = fetcherSpy(appointment);
    const result = await detailAfterAuth({ authenticated: false }, 'tenant-key', fetcher.fn);
    expect(result).toEqual({ kind: 'auth_required' });
    expect(fetcher.calls).toEqual([]);
  });

  it('retrieves the detail after authentication using the scoped reference', async () => {
    const fetcher = fetcherSpy(appointment);
    const result = await detailAfterAuth({ authenticated: true }, 'tenant-key', fetcher.fn);
    expect(fetcher.calls).toEqual(['tenant-key']);
    expect(result).toEqual({ kind: 'detail', value: appointment });
  });
});
