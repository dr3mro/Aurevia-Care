/**
 * PAT-03 privacy acceptance: enrollment, recovery, lockout, step-up and support
 * screens are reached before or during re-authentication. A failure must never
 * disclose whether a code, account or patient record exists, or what state it
 * is in. Every identifier in this file is synthetic.
 */
import { render, screen } from '@testing-library/react-native';

import { SUBMISSION_NOTICE, mapInvitationError, type EnrollmentStatus } from './model';
import {
  EnrollmentScreen,
  LockoutScreen,
  RecoveryScreen,
  StepUpPrompt,
  SupportRequestScreen,
  type EnrollmentStrings,
  type LockoutStrings,
  type RecoveryStrings,
  type StepUpStrings,
  type SupportStrings,
} from './screens';

const noOp = (): void => undefined;

/** Fixed labels whose differences would disclose a code's state to an observer. */
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

const stepUpStrings: StepUpStrings = {
  unlink: 'Confirm removing this chart',
  changeContact: 'Confirm contact change',
  confirm: 'Sign in again',
  cancel: 'Cancel',
};

const supportStrings: SupportStrings = {
  title: 'Contact support',
  messageLabel: 'How can we help?',
  submit: 'Send request',
  category: 'Reason for contact',
  invalidCategory: 'Choose a reason.',
  empty: 'Enter a short description.',
  tooLong: 'Description is too long.',
  phi: 'Remove phone numbers or email addresses.',
};

const failureStatuses: readonly EnrollmentStatus[] = ['expired', 'reused', 'locked', 'error'];

describe('PAT-03: no screen reveals whether a record exists', () => {
  it.each(failureStatuses)('shows only the generic enrollment message for %s', async (status) => {
    await render(
      <EnrollmentScreen
        status={status}
        code="abcd1234"
        onCodeChange={noOp}
        onSubmit={noOp}
        strings={enrollmentStrings}
        isRTL={false}
      />,
    );
    const alert = String(screen.getByRole('alert').props.children);
    expect(alert).toBe(enrollmentStrings.invalid);
    expect(alert).not.toContain('expired');
    expect(alert).not.toContain('used');
    expect(alert).not.toContain('locked');
  });

  it.each(failureStatuses)('shows only the generic recovery message for %s', async (status) => {
    await render(
      <RecoveryScreen
        status={status}
        code="abcd1234"
        verification="site facts"
        onCodeChange={noOp}
        onVerificationChange={noOp}
        onSubmit={noOp}
        strings={recoveryStrings}
        isRTL
      />,
    );
    expect(String(screen.getByRole('alert').props.children)).toBe(recoveryStrings.invalid);
  });

  it.each(['idle', 'submitting', 'success'] as const)('renders no alert in the %s state', async (status) => {
    await render(
      <EnrollmentScreen
        status={status}
        code="abcd1234"
        onCodeChange={noOp}
        onSubmit={noOp}
        strings={enrollmentStrings}
        isRTL={false}
      />,
    );
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('maps every server error code to a single generic failure', () => {
    const codes = ['EXPIRED', 'REUSED', 'REVOKED', 'NETWORK', 'SOMETHING_ELSE', ''];
    const failures = codes.map((code) => mapInvitationError(code));
    for (const failure of failures) {
      expect(failure).toEqual(failures[0]);
      expect(JSON.stringify(failure)).not.toContain('SYN-PATIENT-0001');
    }
  });

  it('never echoes record-shaped text in the support notice or alerts', async () => {
    const recordText = 'Patient SYN-PATIENT-0001 born 1985-04-12, MRN SYN-MRN-778899';
    await render(
      <SupportRequestScreen
        category="identity_dispute"
        message={recordText}
        noticeVisible
        onCategoryChange={noOp}
        onMessageChange={noOp}
        onSubmit={noOp}
        strings={supportStrings}
        isRTL={false}
      />,
    );
    expect(screen.getByText(SUBMISSION_NOTICE)).toBeTruthy();
    expect(screen.queryByText(recordText)).toBeNull();
    for (const alert of screen.getAllByRole('alert')) {
      expect(String(alert.props.children)).not.toContain('SYN-PATIENT-0001');
      expect(String(alert.props.children)).not.toContain('SYN-MRN-778899');
    }
  });

  it('renders the lockout timer and support route without any record field', async () => {
    await render(
      <LockoutScreen remainingSeconds={125} onSupport={noOp} strings={lockoutStrings} isRTL={false} />,
    );
    expect(screen.getByText('02:05')).toBeTruthy();
    expect(screen.queryByLabelText('Patient record')).toBeNull();
  });

  it('gates a sensitive change on a sign-in prompt with no credential field', async () => {
    await render(
      <StepUpPrompt
        reason="unlink_chart"
        onConfirm={noOp}
        onCancel={noOp}
        strings={stepUpStrings}
        isRTL={false}
      />,
    );
    expect(screen.queryByLabelText('Credential')).toBeNull();
    expect(screen.queryByLabelText('Patient record')).toBeNull();
    expect(screen.getByRole('button', { name: 'Sign in again' })).toBeTruthy();
  });
});
