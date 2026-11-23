/**
 * PAT-03 clause 1 spec proof: the patient app supports privacy-safe enrollment,
 * recovery and account lockout, and refuses what it must refuse. Every code and
 * identifier below is synthetic. The server remains authoritative for expiry,
 * reuse and lockout decisions; this proof covers the app's client-side support.
 */
import React from 'react';
import { render, screen } from '@testing-library/react-native';

import { createSensitiveActionGuard, type AuthenticationClock } from '../../api/reauthentication';
import {
  SUBMISSION_NOTICE,
  SUPPORT_CATEGORIES,
  buildSubmissionNotice,
  formatLockoutCountdown,
  isFailedStatus,
  isLocked,
  mapInvitationError,
  normalizeInvitationCode,
  validateInvitationCode,
  validateSupportRequest,
  type EnrollmentStatus,
  type SupportDraft,
} from '../../features/enrollment/model';
import {
  EnrollmentScreen,
  LockoutScreen,
  RecoveryScreen,
  type EnrollmentStrings,
  type LockoutStrings,
  type RecoveryStrings,
} from '../../features/enrollment/screens';

/** Mutable synthetic clock: the freshness window never depends on wall time. */
function clock(at: number): AuthenticationClock & { set(value: number): void } {
  let current = at;
  return {
    now: () => current,
    set: (value: number) => {
      current = value;
    },
  };
}

const FRESHNESS_MS = 5 * 60_000;
const T0 = 1_000_000;
const noOp = (): void => undefined;
const failureStatuses: readonly EnrollmentStatus[] = ['expired', 'reused', 'locked', 'error'];

/** Distinct labels whose presence would disclose which terminal state occurred. */
const enrollmentStrings: EnrollmentStrings = {
  codeLabel: 'Invitation code',
  codeHint: 'Enter the single-use code from your clinic',
  submit: 'Verify code',
  expired: 'This code has expired',
  reused: 'This code has already been used',
  locked: 'Access is temporarily locked',
  invalid: 'That code could not be verified',
};

const recoveryStrings: RecoveryStrings = {
  codeLabel: 'Recovery code',
  verificationLabel: 'Verification detail',
  submit: 'Recover access',
  expired: 'Recovery code expired',
  reused: 'Recovery code used',
  locked: 'Access is temporarily locked',
  invalid: 'Verification failed',
};

const lockoutStrings: LockoutStrings = {
  title: 'Access is locked',
  support: 'Contact support',
  retry: 'Try again',
  timerLabel: 'Time remaining',
};

describe('PAT-03 clause 1: privacy-safe enrollment, recovery and account lockout', () => {
  it('allows a sensitive account change after a fresh sign-in', () => {
    const c = clock(T0);
    const guard = createSensitiveActionGuard({ clock: c, freshnessMs: FRESHNESS_MS });
    guard.recordAuthentication();
    c.set(T0 + FRESHNESS_MS);

    expect(guard.authorizeSensitiveAction()).toEqual({ allowed: true, reason: 'fresh' });
  });

  it('requires re-authentication when no sign-in has been recorded', () => {
    const guard = createSensitiveActionGuard({ clock: clock(T0), freshnessMs: FRESHNESS_MS });

    expect(guard.authorizeSensitiveAction()).toEqual({ allowed: false, reason: 'reauth_required' });
  });

  it('requires re-authentication once the sign-in is older than the freshness window', () => {
    const c = clock(T0);
    const guard = createSensitiveActionGuard({ clock: c, freshnessMs: FRESHNESS_MS });
    guard.recordAuthentication();
    c.set(T0 + FRESHNESS_MS + 1);

    expect(guard.authorizeSensitiveAction()).toEqual({ allowed: false, reason: 'reauth_required' });
  });

  it('normalizes and validates an invitation code before submission', () => {
    const code = normalizeInvitationCode(' ab-cd 12-34 ');

    expect(code).toBe('ABCD1234');
    expect(validateInvitationCode(code)).toEqual({ valid: true });
    expect(validateInvitationCode('')).toEqual({ valid: false, reason: 'empty' });
    expect(validateInvitationCode('AB!')).toEqual({ valid: false, reason: 'malformed' });
  });

  it('maps every invitation error to one generic failure without echoing the input', () => {
    // The last code is patient-shaped and code-shaped, so the no-echo assertion
    // is non-vacuous: the sentinel is an argument, not an unrelated fixture.
    const codes = ['EXPIRED', 'REUSED', 'REVOKED', 'NETWORK', '', 'SYN-PATIENT-0001/ABCD1234'];

    for (const code of codes) {
      const failure = mapInvitationError(code);
      expect(failure).toEqual({ messageKey: 'enrollment.invalid', retryable: true });
      expect(JSON.stringify(failure)).not.toContain('SYN-PATIENT-0001');
      expect(JSON.stringify(failure)).not.toContain('ABCD1234');
    }
  });

  it.each([
    [{ category: null, message: 'Please help me access my record' }, 'category'],
    [{ category: 'accessibility', message: '   ' }, 'empty'],
    [{ category: 'record_access', message: 'x'.repeat(501) }, 'too_long'],
    [{ category: 'identity_dispute', message: 'call 1234567' }, 'phi'],
    [{ category: 'accessibility', message: 'write to patient@example.org' }, 'phi'],
  ])('refuses an unsupported or PHI-bearing support draft (%s)', (draft, reason) => {
    expect(validateSupportRequest(draft as SupportDraft)).toEqual({ ok: false, reason });
  });

  it('accepts a valid support draft for every purpose and returns a generic notice', () => {
    for (const category of SUPPORT_CATEGORIES) {
      expect(validateSupportRequest({ category, message: 'Please help me access my record' })).toEqual({
        ok: true,
      });
    }
    expect(buildSubmissionNotice()).toBe(SUBMISSION_NOTICE);
    expect(buildSubmissionNotice()).not.toContain('patient@example.org');
  });

  it.each(failureStatuses)('shows only the generic enrollment message for %s', async (status) => {
    expect(isFailedStatus(status)).toBe(true);
    await render(
      React.createElement(EnrollmentScreen, {
        status,
        code: 'abcd1234',
        onCodeChange: noOp,
        onSubmit: noOp,
        strings: enrollmentStrings,
        isRTL: false,
      }),
    );

    const alert = String(screen.getByRole('alert').props.children);
    expect(alert).toBe(enrollmentStrings.invalid);
    expect(alert).not.toContain('expired');
    expect(alert).not.toContain('used');
    expect(alert).not.toContain('locked');
  });

  it.each(failureStatuses)('shows only the generic recovery message for %s', async (status) => {
    await render(
      React.createElement(RecoveryScreen, {
        status,
        code: 'abcd1234',
        verification: 'site facts',
        onCodeChange: noOp,
        onVerificationChange: noOp,
        onSubmit: noOp,
        strings: recoveryStrings,
        isRTL: true,
      }),
    );

    const alert = String(screen.getByRole('alert').props.children);
    expect(alert).toBe(recoveryStrings.invalid);
    expect(alert).not.toContain('expired');
    expect(alert).not.toContain('used');
    expect(alert).not.toContain('locked');
  });

  it('shows only a lockout countdown and a support route, with no record field', async () => {
    expect(isLocked(125)).toBe(true);
    expect(formatLockoutCountdown(125)).toBe('02:05');
    expect(isLocked(0)).toBe(false);
    expect(formatLockoutCountdown(0)).toBe('00:00');

    await render(
      React.createElement(LockoutScreen, {
        remainingSeconds: 125,
        onSupport: noOp,
        strings: lockoutStrings,
        isRTL: false,
      }),
    );

    expect(screen.getByText('02:05')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Contact support' })).toBeTruthy();
    expect(screen.queryByText('SYN-PATIENT-0001')).toBeNull();
  });
});
