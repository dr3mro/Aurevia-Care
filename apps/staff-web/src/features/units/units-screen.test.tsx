import { useState } from 'react';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import { alphaProfile, betaProfile, todayCivilDate } from './fixtures';
import { unitsText } from './messages';
import type { ProfileDraft, ServiceProfile } from './types';
import { UnitsScreen } from './UnitsScreen';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Own the profile so command callbacks can switch the rendered state. */
function Harness({
  initial = alphaProfile,
  onPropose = vi.fn(async () => true),
  onApprove = vi.fn(async () => true),
  onEnable = vi.fn(async () => true),
  onDisable = vi.fn(async () => true),
}: {
  initial?: ServiceProfile;
  onPropose?: (draft: ProfileDraft) => Promise<boolean>;
  onApprove?: () => Promise<boolean>;
  onEnable?: () => Promise<boolean>;
  onDisable?: () => Promise<boolean>;
}) {
  const [profile] = useState(initial);
  return (
    <UnitsScreen
      profile={profile}
      todayCivilDate={todayCivilDate}
      onPropose={onPropose}
      onApprove={onApprove}
      onEnable={onEnable}
      onDisable={onDisable}
    />
  );
}

function change(field: string, value: string) {
  fireEvent.change(screen.getByLabelText(unitsText('en', field as 'serviceHours')), {
    target: { value },
  });
}

describe('UnitsScreen (MSG-06, ADM-01)', () => {
  it('publishes service hours, targets, topics and urgent guidance before sending', () => {
    wrap(<Harness />);
    expect(screen.getByRole('heading', { name: unitsText('en', 'serviceProfile') })).toBeTruthy();
    expect(screen.getByText(alphaProfile.details.serviceHours)).toBeTruthy();
    expect(screen.getByText(alphaProfile.details.responseTargetUrgent)).toBeTruthy();
    expect(screen.getByText(alphaProfile.details.responseTargetRoutine)).toBeTruthy();
    expect(screen.getByText(alphaProfile.details.topics)).toBeTruthy();
    expect(screen.getByText(alphaProfile.details.urgentContactInstructions)).toBeTruthy();
    expect(screen.getByText(alphaProfile.details.queueOwner)).toBeTruthy();
    expect(screen.getByText(unitsText('en', 'requiredHint'))).toBeTruthy();
  });

  it('shows an operational warning when no backup coverage is named', () => {
    wrap(<Harness />);
    const warning = screen.getByTestId('units-warning');
    expect(warning.textContent).toContain(unitsText('en', 'noBackupWarning'));
    expect(warning.textContent).toContain(unitsText('en', 'noBackupDetail'));
    expect(screen.getByText(unitsText('en', 'notRecorded'))).toBeTruthy();
  });

  it('hides the operational warning when backup coverage is named', () => {
    wrap(<Harness initial={betaProfile} />);
    expect(screen.queryByTestId('units-warning')).toBeNull();
    expect(screen.getByText(betaProfile.details.backupCoverage)).toBeTruthy();
  });

  it('blocks enabling a unit with no named queue owner', () => {
    const onEnable = vi.fn(async () => true);
    wrap(<Harness initial={{ ...betaProfile, enabled: false }} onEnable={onEnable} />);
    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'enableUnit') }));
    expect(onEnable).not.toHaveBeenCalled();
    expect(screen.getByTestId('units-refusal').textContent).toBe(unitsText('en', 'missingOwner'));
    expect(screen.getByLabelText(unitsText('en', 'queueOwner')).getAttribute('aria-invalid')).toBe('true');
  });

  it('enables a unit that has a named queue owner', async () => {
    const onEnable = vi.fn(async () => true);
    wrap(<Harness onEnable={onEnable} />);
    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'enableUnit') }));
    expect(onEnable).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(screen.getByTestId('units-status').textContent).toBe(unitsText('en', 'unitEnabled')),
    );
  });

  it('disables an enabled unit', async () => {
    const onDisable = vi.fn(async () => true);
    wrap(<Harness initial={betaProfile} onDisable={onDisable} />);
    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'disableUnit') }));
    expect(onDisable).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(screen.getByTestId('units-status').textContent).toBe(unitsText('en', 'unitDisabled')),
    );
  });

  it('refuses a proposed change whose effective date is in the past', () => {
    const onPropose = vi.fn(async () => true);
    wrap(<Harness onPropose={onPropose} />);
    change('effectiveFrom', '2026-03-08');
    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'proposeChanges') }));
    expect(onPropose).not.toHaveBeenCalled();
    const summary = screen.getByTestId('units-refusal');
    expect(summary.textContent).toBe(unitsText('en', 'pastEffectiveDate'));
    expect(document.activeElement).toBe(summary);
    expect(screen.getByLabelText(unitsText('en', 'effectiveFrom')).getAttribute('aria-invalid')).toBe('true');
  });

  it('refuses a proposed change without a named approver', () => {
    const onPropose = vi.fn(async () => true);
    wrap(<Harness onPropose={onPropose} />);
    change('namedApprover', '');
    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'proposeChanges') }));
    expect(onPropose).not.toHaveBeenCalled();
    expect(screen.getByTestId('units-refusal').textContent).toBe(unitsText('en', 'missingApprover'));
  });

  it('refuses a malformed effective date', () => {
    const onPropose = vi.fn(async () => true);
    wrap(<Harness onPropose={onPropose} />);
    change('effectiveFrom', 'not-a-date');
    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'proposeChanges') }));
    expect(onPropose).not.toHaveBeenCalled();
    expect(screen.getByTestId('units-refusal').textContent).toBe(
      unitsText('en', 'invalidEffectiveDate'),
    );
  });

  it('sends the whole approved, future-dated draft and announces the change', async () => {
    const onPropose = vi.fn(async () => true);
    wrap(<Harness onPropose={onPropose} />);
    change('topics', 'appointments, urgent guidance');
    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'proposeChanges') }));

    expect(onPropose).toHaveBeenCalledWith(
      expect.objectContaining({
        topics: 'appointments, urgent guidance',
        approver: 'Synthetic Clinical Lead',
        effectiveFrom: '2026-03-10',
      }),
    );
    expect(screen.getByTestId('units-refusal').textContent).toBe('');
    await waitFor(() =>
      expect(screen.getByTestId('units-status').textContent).toBe(unitsText('en', 'proposed')),
    );
  });

  it('edits every published service field before proposing', () => {
    const onPropose = vi.fn(async () => true);
    wrap(<Harness onPropose={onPropose} />);
    change('serviceHours', 'Sat 09:00-13:00');
    change('responseTargetUrgent', '5 minutes');
    change('responseTargetRoutine', '2 hours');
    change('urgentContactInstructions', 'Use the synthetic escalation channel');
    change('queueOwner', 'Synthetic Replacement Lead');
    change('backupCoverage', 'Synthetic Second Backup');
    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'proposeChanges') }));

    expect(onPropose).toHaveBeenCalledWith(
      expect.objectContaining({
        serviceHours: 'Sat 09:00-13:00',
        responseTargetUrgent: '5 minutes',
        responseTargetRoutine: '2 hours',
        urgentContactInstructions: 'Use the synthetic escalation channel',
        queueOwner: 'Synthetic Replacement Lead',
        backupCoverage: 'Synthetic Second Backup',
      }),
    );
  });

  it('approves a pending version and shows the approval state', async () => {
    const onApprove = vi.fn(async () => true);
    wrap(<Harness onApprove={onApprove} />);
    expect(screen.getByText(unitsText('en', 'pendingApproval'))).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: unitsText('en', 'approve') }));
    expect(onApprove).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(screen.getByTestId('units-status').textContent).toBe(unitsText('en', 'approved')),
    );
    wrap(<Harness initial={betaProfile} />);
    expect(screen.getByText(unitsText('en', 'approvedBadge'))).toBeTruthy();
  });

  it('reaches the primary action by keyboard and activates the form submit path', () => {
    const onPropose = vi.fn(async () => true);
    wrap(<Harness onPropose={onPropose} />);
    const propose = screen.getByRole('button', { name: unitsText('en', 'proposeChanges') });
    propose.focus();
    expect(document.activeElement).toBe(propose);
    expect(propose.getAttribute('type')).toBe('submit');
    const form = propose.closest('form');
    if (!form) {
      throw new Error('propose button is not inside a form');
    }
    // jsdom does not synthesise a click from the Enter key; the native submit
    // path that Enter activates in a browser is exercised directly.
    fireEvent.submit(form);
    expect(onPropose).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(propose);
  });

  it.each([
    ['propose', alphaProfile, 'proposeChanges'],
    ['approve', alphaProfile, 'approve'],
    ['enable', { ...betaProfile, enabled: false }, 'enableUnit'],
    ['disable', betaProfile, 'disableUnit'],
  ] as const)('does not announce %s success when the page reports failure', async (_command, profile, button) => {
    const fail = async () => false;
    wrap(
      <Harness
        initial={profile}
        onPropose={fail}
        onApprove={fail}
        onEnable={fail}
        onDisable={fail}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: unitsText('en', button) }));
    await waitFor(() => expect(screen.getByTestId('units-status').textContent).toBe(''));
  });

  it('isolates an Arabic-script owner with its own language and direction', () => {
    wrap(
      <Harness
        initial={{
          ...alphaProfile,
          details: { ...alphaProfile.details, queueOwner: 'مالك تجريبي' },
        }}
      />,
      'ar',
    );
    const owner = screen.getByText('مالك تجريبي');
    expect(owner.getAttribute('lang')).toBe('ar');
    expect(owner.getAttribute('dir')).toBe('auto');
  });

  it('renders Arabic copy with right-to-left direction and the same states', () => {
    wrap(<Harness />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { name: unitsText('ar', 'serviceProfile') })).toBeTruthy();
    expect(screen.getByTestId('units-warning').textContent).toContain(
      unitsText('ar', 'noBackupDetail'),
    );
    expect(screen.getByRole('button', { name: unitsText('ar', 'proposeChanges') })).toBeTruthy();
  });
});
