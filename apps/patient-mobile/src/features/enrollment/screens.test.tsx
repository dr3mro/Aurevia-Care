import { fireEvent, render, screen } from '@testing-library/react-native';

import {
  EnrollmentScreen,
  LockoutScreen,
  RecoveryScreen,
  StepUpPrompt,
  SupportRequestScreen,
  type EnrollmentStrings,
  type RecoveryStrings,
  type SupportStrings,
} from './screens';
import { SUBMISSION_NOTICE } from './model';

/** Minimal typed spy that records calls without depending on a test-runner global. */
type Spy<Args extends unknown[]> = ((...args: Args) => void) & { calls: Args[] };

function spy<Args extends unknown[]>(): Spy<Args> {
  const calls: Args[] = [];
  const fn = ((...args: Args): void => {
    calls.push(args);
  }) as Spy<Args>;
  fn.calls = calls;
  return fn;
}

const enrollmentStrings: EnrollmentStrings = {
  codeLabel: 'Invitation code',
  codeHint: 'Enter the code from your clinic',
  submit: 'Enroll',
  expired: 'This code has expired',
  reused: 'This code has already been used',
  locked: 'This account is locked',
  invalid: 'Check the code and try again',
};

const recoveryStrings: RecoveryStrings = {
  codeLabel: 'Recovery code',
  verificationLabel: 'Verification facts',
  submit: 'Restore access',
  expired: 'Recovery code expired',
  reused: 'Recovery code used',
  locked: 'Account locked',
  invalid: 'Verification failed',
};

const supportStrings: SupportStrings = {
  title: 'Contact support',
  messageLabel: 'Your request',
  submit: 'Send request',
  category: 'Reason',
  invalidCategory: 'Choose a reason',
  empty: 'Write a short request',
  tooLong: 'Request is too long',
  phi: 'Remove phone numbers or email addresses',
};

describe('EnrollmentScreen', () => {
  it('collects a code and submits it normalized', async () => {
    const onSubmit = spy<[string]>();
    const onCodeChange = spy<[string]>();
    await render(
      <EnrollmentScreen
        status="idle"
        code="ab-cd 1234"
        onCodeChange={onCodeChange}
        onSubmit={onSubmit}
        strings={enrollmentStrings}
        isRTL={false}
      />,
    );
    await fireEvent.changeText(screen.getByLabelText('Invitation code'), 'abcd1234');
    expect(onCodeChange.calls).toEqual([['abcd1234']]);

    await fireEvent.press(screen.getByRole('button', { name: 'Enroll' }));
    expect(onSubmit.calls).toEqual([['ABCD1234']]);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it.each([
    ['expired', 'This code has expired'],
    ['reused', 'This code has already been used'],
    ['locked', 'This account is locked'],
    ['error', 'Check the code and try again'],
  ])('shows the %s state without echoing the code', async (status, message) => {
    await render(
      <EnrollmentScreen
        status={status as 'expired'}
        code="abcd1234"
        onCodeChange={spy<[string]>()}
        onSubmit={spy<[string]>()}
        strings={enrollmentStrings}
        isRTL
      />,
    );
    expect(screen.getByText(message)).toBeTruthy();
    expect(screen.queryByText('abcd1234')).toBeNull();
  });

  it('disables submit for a malformed code', async () => {
    await render(
      <EnrollmentScreen
        status="idle"
        code="!"
        onCodeChange={spy<[string]>()}
        onSubmit={spy<[string]>()}
        strings={enrollmentStrings}
        isRTL={false}
      />,
    );
    expect(screen.getByRole('button', { name: 'Enroll' }).props.accessibilityState).toEqual({ disabled: true });
  });
});

describe('RecoveryScreen', () => {
  it('submits a normalized code and trimmed verification facts', async () => {
    const onSubmit = spy<[string, string]>();
    const onCodeChange = spy<[string]>();
    const onVerificationChange = spy<[string]>();
    await render(
      <RecoveryScreen
        status="idle"
        code="ab-cd 1234"
        verification="  site-facts  "
        onCodeChange={onCodeChange}
        onVerificationChange={onVerificationChange}
        onSubmit={onSubmit}
        strings={recoveryStrings}
        isRTL={false}
      />,
    );
    await fireEvent.changeText(screen.getByLabelText('Recovery code'), 'abcd1234');
    await fireEvent.changeText(screen.getByLabelText('Verification facts'), 'site-facts');
    expect(onCodeChange.calls).toEqual([['abcd1234']]);
    expect(onVerificationChange.calls).toEqual([['site-facts']]);
    await fireEvent.press(screen.getByRole('button', { name: 'Restore access' }));
    expect(onSubmit.calls).toEqual([['ABCD1234', 'site-facts']]);
  });

  it('disables submit when either field is empty', async () => {
    const { rerender } = await render(
      <RecoveryScreen
        status="idle"
        code="!"
        verification="facts"
        onCodeChange={spy<[string]>()}
        onVerificationChange={spy<[string]>()}
        onSubmit={spy<[string, string]>()}
        strings={recoveryStrings}
        isRTL={false}
      />,
    );
    expect(screen.getByRole('button', { name: 'Restore access' }).props.accessibilityState).toEqual({ disabled: true });
    await rerender(
      <RecoveryScreen
        status="idle"
        code="ABCD1234"
        verification="   "
        onCodeChange={spy<[string]>()}
        onVerificationChange={spy<[string]>()}
        onSubmit={spy<[string, string]>()}
        strings={recoveryStrings}
        isRTL={false}
      />,
    );
    expect(screen.getByRole('button', { name: 'Restore access' }).props.accessibilityState).toEqual({ disabled: true });
  });

  it.each([
    ['expired', 'Recovery code expired'],
    ['reused', 'Recovery code used'],
    ['locked', 'Account locked'],
    ['error', 'Verification failed'],
  ])('shows the %s recovery state', async (status, message) => {
    await render(
      <RecoveryScreen
        status={status as 'expired'}
        code="ABCD1234"
        verification="facts"
        onCodeChange={spy<[string]>()}
        onVerificationChange={spy<[string]>()}
        onSubmit={spy<[string, string]>()}
        strings={recoveryStrings}
        isRTL
      />,
    );
    expect(screen.getByText(message)).toBeTruthy();
  });
});

describe('LockoutScreen', () => {
  const strings = { title: 'Temporarily locked', support: 'Get help', retry: 'Try again', timerLabel: 'Time remaining' };

  it('shows a countdown and the support route', async () => {
    const onSupport = spy<[]>();
    await render(<LockoutScreen remainingSeconds={125} onSupport={onSupport} strings={strings} isRTL={false} />);
    expect(screen.getByText('02:05')).toBeTruthy();
    await fireEvent.press(screen.getByRole('button', { name: 'Get help' }));
    expect(onSupport.calls.length).toBe(1);
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  });

  it('offers a retry action when the parent supplies one', async () => {
    const onRetry = spy<[]>();
    await render(<LockoutScreen remainingSeconds={0} onSupport={spy<[]>()} onRetry={onRetry} strings={strings} isRTL />);
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry.calls.length).toBe(1);
    expect(screen.getByText('00:00')).toBeTruthy();
  });
});

describe('StepUpPrompt', () => {
  const strings = {
    unlink: 'Confirm removing this chart',
    changeContact: 'Confirm contact change',
    confirm: 'Confirm',
    cancel: 'Cancel',
  };

  it.each([
    ['unlink_chart', 'Confirm removing this chart'],
    ['change_contact', 'Confirm contact change'],
  ])('explains the %s reason and has no credential field', async (reason, explanation) => {
    const onConfirm = spy<[]>();
    const onCancel = spy<[]>();
    await render(
      <StepUpPrompt
        reason={reason as 'unlink_chart'}
        onConfirm={onConfirm}
        onCancel={onCancel}
        strings={strings}
        isRTL
      />,
    );
    expect(screen.getByText(explanation)).toBeTruthy();
    expect(screen.queryByLabelText('Credential')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Confirm' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));
    expect(onConfirm.calls.length).toBe(1);
    expect(onCancel.calls.length).toBe(1);
  });
});

describe('SupportRequestScreen', () => {
  const base = {
    message: 'Please help me access my record',
    noticeVisible: false,
    onCategoryChange: spy<['identity_dispute' | 'accessibility' | 'record_access']>(),
    onMessageChange: spy<[string]>(),
    onSubmit: spy<[]>(),
    strings: supportStrings,
    isRTL: false,
  };

  it('lets the patient choose each category and type a request', async () => {
    const onCategoryChange = spy<['identity_dispute' | 'accessibility' | 'record_access']>();
    const onMessageChange = spy<[string]>();
    await render(
      <SupportRequestScreen
        {...base}
        category="accessibility"
        onCategoryChange={onCategoryChange}
        onMessageChange={onMessageChange}
      />,
    );
    for (const category of ['identity_dispute', 'accessibility', 'record_access'] as const) {
      await fireEvent.press(screen.getByRole('radio', { name: category }));
    }
    expect(onCategoryChange.calls.map((call) => call[0])).toEqual([
      'identity_dispute',
      'accessibility',
      'record_access',
    ]);
    await fireEvent.changeText(screen.getByLabelText('Your request'), 'new request');
    expect(onMessageChange.calls).toEqual([['new request']]);
  });

  it.each([
    [null, '', 'Choose a reason'],
    ['accessibility', '   ', 'Write a short request'],
    ['accessibility', 'x'.repeat(501), 'Request is too long'],
    ['record_access', 'call 1234567', 'Remove phone numbers or email addresses'],
  ])('blocks an invalid request and explains why', async (category, message, error) => {
    await render(<SupportRequestScreen {...base} category={category as null} message={message as string} />);
    expect(screen.getByText(error)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Send request' }).props.accessibilityState).toEqual({ disabled: true });
  });

  it('submits a valid request and shows only the generic notice', async () => {
    const onSubmit = spy<[]>();
    await render(
      <SupportRequestScreen {...base} category="identity_dispute" noticeVisible onSubmit={onSubmit} isRTL />,
    );
    expect(screen.queryByRole('alert', { name: 'Remove phone numbers or email addresses' })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Send request' }));
    expect(onSubmit.calls.length).toBe(1);
    expect(screen.getByText(SUBMISSION_NOTICE)).toBeTruthy();
  });
});
