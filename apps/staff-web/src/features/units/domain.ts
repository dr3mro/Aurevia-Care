import type { ProfileDraft, ProfileValidation, ServiceDetails, ServiceProfile } from './types';

/** True when a named backup is staffed for the unit (MSG-06). */
export function hasBackupCoverage(details: Pick<ServiceDetails, 'backupCoverage'>): boolean {
  return details.backupCoverage.trim().length > 0;
}

/** A unit may only be enabled when a named queue owner is staffed (MSG-06). */
export function canEnable(profile: ServiceProfile): boolean {
  return profile.details.queueOwner.trim().length > 0;
}

/**
 * Resolve the display label of a named subject, using an explicit word when it
 * is absent instead of an empty cell or a bare dash (UX-01).
 */
export function displaySubject(subject: string, absentLabel: string): string {
  return subject.trim() === '' ? absentLabel : subject;
}

/**
 * Accept only a real calendar date in `YYYY-MM-DD` form so the screen never
 * passes an invalid instant to `Intl.DateTimeFormat` (a RangeError crash path).
 */
export function isValidCivilDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const parsed = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime())) {
    return false;
  }
  return parsed.toISOString().slice(0, 10) === value;
}

/** True when the proposed effective date is earlier than today's civil date. */
export function isPastEffectiveDate(civilDate: string, todayCivilDate: string): boolean {
  return civilDate < todayCivilDate;
}

/**
 * Validate a proposed change before it is sent: a named approver and a valid,
 * not-yet-past effective date are required by ADM-01. The checks are ordered so
 * the first problem the person can fix is the one reported.
 */
export function validateProfile(draft: ProfileDraft, todayCivilDate: string): ProfileValidation {
  if (draft.approver.trim().length === 0) {
    return { ok: false, reason: 'missing-approver' };
  }
  if (!isValidCivilDate(draft.effectiveFrom)) {
    return { ok: false, reason: 'invalid-effective-date' };
  }
  if (isPastEffectiveDate(draft.effectiveFrom, todayCivilDate)) {
    return { ok: false, reason: 'past-effective-date' };
  }
  return { ok: true };
}
