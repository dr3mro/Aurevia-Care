import { describe, expect, it } from 'vitest';
import {
  canEnable,
  displaySubject,
  hasBackupCoverage,
  isPastEffectiveDate,
  isValidCivilDate,
  validateProfile,
} from './domain';
import { alphaProfile } from './fixtures';
import type { ProfileDraft, ServiceProfile } from './types';

/** A fully populated synthetic draft; individual tests override one field. */
function draft(overrides: Partial<ProfileDraft> = {}): ProfileDraft {
  return {
    serviceHours: 'Mon-Fri 09:00-17:00',
    responseTargetUrgent: '15 minutes',
    responseTargetRoutine: '1 business day',
    topics: 'appointments, results',
    urgentContactInstructions: 'Call the synthetic unit on-call phone',
    queueOwner: 'synthetic.owner',
    backupCoverage: 'synthetic.backup',
    approver: 'synthetic.approver',
    effectiveFrom: '2026-03-10',
    ...overrides,
  };
}

/** A synthetic profile with a chosen owner. */
function profileWithOwner(queueOwner: string): ServiceProfile {
  return {
    ...alphaProfile,
    details: { ...alphaProfile.details, queueOwner },
  };
}

describe('unit service profile coverage rules (MSG-06)', () => {
  it('recognises named backup coverage', () => {
    expect(hasBackupCoverage(draft())).toBe(true);
  });

  it('treats an empty or whitespace-only backup as no coverage', () => {
    expect(hasBackupCoverage(draft({ backupCoverage: '' }))).toBe(false);
    expect(hasBackupCoverage(draft({ backupCoverage: '   ' }))).toBe(false);
  });

  it('allows enabling a unit with a named queue owner', () => {
    expect(canEnable(profileWithOwner('synthetic.owner'))).toBe(true);
  });

  it('blocks enabling a unit whose queue owner is missing or blank', () => {
    expect(canEnable(profileWithOwner(''))).toBe(false);
    expect(canEnable(profileWithOwner('  '))).toBe(false);
  });

  it('shows an explicit label when a named subject is absent', () => {
    expect(displaySubject('synthetic.owner', 'Not recorded')).toBe('synthetic.owner');
    expect(displaySubject('', 'Not recorded')).toBe('Not recorded');
    expect(displaySubject('  ', 'Not recorded')).toBe('Not recorded');
  });
});

describe('effective date validation (ADM-01)', () => {
  it('accepts a real civil date', () => {
    expect(isValidCivilDate('2026-03-10')).toBe(true);
  });

  it('refuses a malformed civil date', () => {
    expect(isValidCivilDate('10/03/2026')).toBe(false);
  });

  it('refuses an impossible calendar date', () => {
    expect(isValidCivilDate('2026-13-01')).toBe(false);
  });

  it('refuses a date that rolls over to another day', () => {
    expect(isValidCivilDate('2026-02-30')).toBe(false);
  });

  it('distinguishes a past, today and future effective date', () => {
    expect(isPastEffectiveDate('2026-03-08', '2026-03-09')).toBe(true);
    expect(isPastEffectiveDate('2026-03-09', '2026-03-09')).toBe(false);
    expect(isPastEffectiveDate('2026-03-10', '2026-03-09')).toBe(false);
  });
});

describe('service profile change validation (MSG-06, ADM-01)', () => {
  it('refuses a change without a named approver', () => {
    expect(validateProfile(draft({ approver: '' }), '2026-03-09')).toEqual({
      ok: false,
      reason: 'missing-approver',
    });
  });

  it('refuses a change with an invalid effective date', () => {
    expect(validateProfile(draft({ effectiveFrom: 'not-a-date' }), '2026-03-09')).toEqual({
      ok: false,
      reason: 'invalid-effective-date',
    });
  });

  it('refuses a change whose effective date is in the past', () => {
    expect(validateProfile(draft({ effectiveFrom: '2026-03-08' }), '2026-03-09')).toEqual({
      ok: false,
      reason: 'past-effective-date',
    });
  });

  it('accepts a fully approved, future-dated change', () => {
    expect(validateProfile(draft(), '2026-03-09')).toEqual({ ok: true });
  });
});
