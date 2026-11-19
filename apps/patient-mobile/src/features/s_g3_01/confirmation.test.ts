import type { Appointment } from './types';
import { confirm, deriveConfirmation } from './confirmation';

/** S-G3-01: confirmation eligibility, enablement and server-style timestamp (PAT-01). */
const booked: Appointment = {
  id: 'apt-1',
  patientId: 'patient-1',
  facilityCode: 'MAIN',
  facilityTimeZone: 'Asia/Riyadh',
  startsAt: '2026-12-01T07:00:00Z',
  status: 'booked',
  confirmationEnabled: true,
  confirmedAt: null,
};

describe('deriveConfirmation', () => {
  it('is pending only for an enabled, confirmable, unconfirmed appointment', () => {
    expect(deriveConfirmation(booked, true)).toEqual({ kind: 'pending', confirmedAt: null });
  });

  it('is unavailable when confirmation is disabled even for a booked visit', () => {
    expect(deriveConfirmation(booked, false)).toEqual({ kind: 'unavailable', confirmedAt: null });
  });

  it('is unavailable for a status past confirmation', () => {
    expect(deriveConfirmation({ ...booked, status: 'checked_in' }, true)).toEqual({
      kind: 'unavailable',
      confirmedAt: null,
    });
    expect(deriveConfirmation({ ...booked, status: 'cancelled' }, true)).toEqual({
      kind: 'unavailable',
      confirmedAt: null,
    });
  });

  it('is confirmed when a timestamp is already recorded', () => {
    expect(deriveConfirmation({ ...booked, confirmedAt: '2026-11-19T10:00:00Z' }, true)).toEqual({
      kind: 'confirmed',
      confirmedAt: '2026-11-19T10:00:00Z',
    });
  });
});

describe('confirm', () => {
  it('records the supplied instant on an eligible authenticated transition', () => {
    expect(confirm({ kind: 'pending', confirmedAt: null }, true, '2026-11-19T10:00:00Z')).toEqual({
      kind: 'confirmed',
      confirmedAt: '2026-11-19T10:00:00Z',
    });
  });

  it('refuses an unauthenticated confirmation without a timestamp', () => {
    expect(confirm({ kind: 'pending', confirmedAt: null }, false, '2026-11-19T10:00:00Z')).toEqual({
      kind: 'error',
      reason: 'UNAUTHENTICATED',
    });
  });

  it('is idempotent for an already confirmed state and never overwrites the first time', () => {
    expect(confirm({ kind: 'confirmed', confirmedAt: '2026-11-19T10:00:00Z' }, true, 'later')).toEqual({
      kind: 'idempotent',
      confirmedAt: '2026-11-19T10:00:00Z',
    });
  });

  it('refuses an ineligible appointment', () => {
    expect(confirm({ kind: 'unavailable', confirmedAt: null }, true, 'now')).toEqual({
      kind: 'error',
      reason: 'NOT_ELIGIBLE',
    });
  });
});
