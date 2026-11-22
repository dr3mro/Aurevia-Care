import type { Appointment, ZoneFormatter } from './types';
import { appointmentView, confirmedAppointmentView, sortByScheduled } from './appointments';

/** S-G3-01: appointment list projection with facility time zone and status (PAT-01). */
const formatter: ZoneFormatter = {
  format: (instant, zone) => `${instant}@${zone}`,
};

/**
 * The real facility-zone formatter the authenticated app injects. It uses the
 * IANA database, so it is daylight-saving aware; using it here keeps the
 * projection tests faithful to the production path (PAT-01, UX-01).
 */
const realFormatter: ZoneFormatter = {
  format: (instant, zone) =>
    new Intl.DateTimeFormat('en-GB', {
      timeZone: zone,
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(new Date(instant)),
};

const appointmentAt = (startsAt: string): Appointment => ({
  id: 'apt-dst',
  version: 3,
  patientId: 'patient-1',
  facilityCode: 'NYC',
  facilityTimeZone: 'America/New_York',
  startsAt,
  status: 'booked',
  confirmationEnabled: true,
  confirmedAt: null,
});

describe('appointmentView', () => {
  it('keeps the facility time zone, status and derived confirmation state', () => {
    const view = appointmentView(
      {
        id: 'apt-1',
        version: 3,
        patientId: 'patient-1',
        facilityCode: 'MAIN',
        facilityTimeZone: 'Asia/Riyadh',
        startsAt: '2026-12-01T07:00:00Z',
        status: 'booked',
        confirmationEnabled: true,
        confirmedAt: null,
      },
      'org-a',
      formatter,
      { kind: 'pending', confirmedAt: null },
    );
    expect(view).toEqual({
      key: 'org-a\u0000apt-1',
      id: 'apt-1',
      version: 3,
      timeZone: 'Asia/Riyadh',
      localStart: '2026-12-01T07:00:00Z@Asia/Riyadh',
      status: 'booked',
      confirmation: { kind: 'pending', confirmedAt: null },
      confirmedLocal: null,
    });
    expect(formatter.format).toBeDefined();
  });

  it('formats the recorded confirmation instant in the facility zone when confirmed', () => {
    const view = appointmentView(
      appointmentAt('2026-11-01T06:30:00Z'),
      'org-a',
      formatter,
      { kind: 'confirmed', confirmedAt: '2026-11-01T05:30:00Z' },
    );
    expect(view.confirmedLocal).toBe('2026-11-01T05:30:00Z@America/New_York');
    expect(view.confirmation).toEqual({
      kind: 'confirmed',
      confirmedAt: '2026-11-01T05:30:00Z',
    });
  });
});

describe('appointmentView daylight-saving boundary (PAT-01, UX-01)', () => {
  it('shows the correct facility local time either side of the spring-forward transition', () => {
    // America/New_York springs forward on 2026-03-08 at 07:00Z:
    // 02:00 EST becomes 03:00 EDT, so the same UTC minute gap maps to a one-hour wall-clock jump.
    const before = appointmentView(
      appointmentAt('2026-03-08T06:59:00Z'),
      'org-a',
      realFormatter,
      { kind: 'pending', confirmedAt: null },
    );
    const after = appointmentView(
      appointmentAt('2026-03-08T07:00:00Z'),
      'org-a',
      realFormatter,
      { kind: 'pending', confirmedAt: null },
    );
    expect(before.localStart).toBe('01:59');
    expect(after.localStart).toBe('03:00');
    expect(before.timeZone).toBe('America/New_York');
    expect(after.timeZone).toBe('America/New_York');
    expect(before.status).toBe('booked');
  });

  it('renders both the appointment and its confirmation in the facility zone across the fall-back overlap', () => {
    // 2026-11-01 falls back at 06:00Z: 05:30Z is 01:30 EDT, 06:30Z is 01:30 EST.
    // The two UTC instants share a wall-clock value, proving storage stays UTC
    // while the displayed time is derived from the IANA zone.
    const view = appointmentView(
      appointmentAt('2026-11-01T06:30:00Z'),
      'org-a',
      realFormatter,
      { kind: 'confirmed', confirmedAt: '2026-11-01T05:30:00Z' },
    );
    expect(view.localStart).toBe('01:30');
    expect(view.confirmedLocal).toBe('01:30');
    expect(view.timeZone).toBe('America/New_York');
  });
});

describe('confirmedAppointmentView (PAT-01)', () => {
  it('applies the server instant in the facility zone and advances the version', () => {
    const row = appointmentView(
      appointmentAt('2026-11-21T10:00:00Z'),
      'org-a',
      realFormatter,
      { kind: 'pending', confirmedAt: null },
    );
    const confirmed = confirmedAppointmentView(
      row,
      '2026-11-21T10:05:00Z',
      4,
      realFormatter,
    );

    expect(confirmed.confirmation).toEqual({
      kind: 'confirmed',
      confirmedAt: '2026-11-21T10:05:00Z',
    });
    expect(confirmed.confirmedLocal).toBe('05:05');
    expect(confirmed.version).toBe(4);
    expect(confirmed.id).toBe(row.id);
    expect(row.confirmation).toEqual({ kind: 'pending', confirmedAt: null });
  });
});

describe('sortByScheduled', () => {
  it('returns a new stable ordering by UTC start without mutating the source', () => {
    const source = [
      { id: 'b', startsAt: '2026-12-02T07:00:00Z' },
      { id: 'a', startsAt: '2026-12-01T07:00:00Z' },
    ];
    const sorted = sortByScheduled(source);
    expect(sorted.map((entry) => entry.id)).toEqual(['a', 'b']);
    expect(source[0]?.id).toBe('b');
  });

  it('handles an empty list', () => {
    expect(sortByScheduled([])).toEqual([]);
  });
});
