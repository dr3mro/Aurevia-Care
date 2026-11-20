import type { ZoneFormatter } from './types';
import { appointmentView, sortByScheduled } from './appointments';

/** S-G3-01: appointment list projection with facility time zone and status (PAT-01). */
const formatter: ZoneFormatter = {
  format: (instant, zone) => `${instant}@${zone}`,
};

describe('appointmentView', () => {
  it('keeps the facility time zone, status and derived confirmation state', () => {
    const view = appointmentView(
      {
        id: 'apt-1',
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
      timeZone: 'Asia/Riyadh',
      localStart: '2026-12-01T07:00:00Z@Asia/Riyadh',
      status: 'booked',
      confirmation: { kind: 'pending', confirmedAt: null },
    });
    expect(formatter.format).toBeDefined();
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
