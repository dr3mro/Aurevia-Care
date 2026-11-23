import { readdirSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { formatInstant } from '../../i18n/format';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import { ConfigEditor } from './ConfigEditor';
import { AccessStateNotice } from './AccessStateNotice';
import { AuditEventList } from './AuditEventList';
import { StaffHistoryView } from './StaffHistoryView';
import { StateBadge } from './StateBadge';
import {
  adminAnchorCivilDate,
  adminConfigurationKey,
  alphaOrganizationId,
  betaOrganizationId,
  syntheticAuditEvents,
  syntheticConfigurations,
  syntheticStaffEvents,
} from './fixtures';
import { adminText } from './messages';
import { must } from '../../../tests/must';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

describe('StateBadge', () => {
  it('renders a translated label beside a decorative glyph', () => {
    wrap(<StateBadge glyph="✓" label="Synthetic verified" tone="verified" testId="badge-1" />);
    const badge = screen.getByTestId('badge-1');
    expect(badge.getAttribute('data-state')).toBe('verified');
    expect(within(badge).getByText('Synthetic verified').tagName).toBe('BDI');
    expect(within(badge).getByText('Synthetic verified').getAttribute('dir')).toBe('auto');
    expect(badge.querySelector('[aria-hidden="true"]')?.textContent).toBe('✓');
  });

  it('does not require a test id', () => {
    wrap(<StateBadge glyph="!" label="Synthetic broken" tone="broken" />);
    expect(screen.getByText('Synthetic broken')).toBeTruthy();
  });
});

describe('AccessStateNotice', () => {
  it('reports denial as an alert and the other states as status', () => {
    for (const state of ['permitted', 'denied', 'concealed'] as const) {
      const { unmount } = wrap(<AccessStateNotice state={state} label="Synthetic probe" />);
      const notice = screen.getByText(
        adminText(
          'en',
          state === 'permitted'
            ? 'accessPermitted'
            : state === 'denied'
              ? 'accessDenied'
              : 'accessConcealed',
        ),
      );
      const panel = notice.closest('[data-access-state]');
      expect(panel?.getAttribute('data-access-state')).toBe(state);
      expect(panel?.getAttribute('role')).toBe(state === 'denied' ? 'alert' : 'status');
      expect(screen.getByText('Synthetic probe')).toBeTruthy();
      unmount();
    }
  });

  it('uses Arabic labels under RTL', () => {
    wrap(<AccessStateNotice state="denied" label="Synthetic probe" />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText(adminText('ar', 'accessDenied'))).toBeTruthy();
  });
});

describe('ConfigEditor', () => {
  const baseProps = {
    entries: syntheticConfigurations,
    organizationId: alphaOrganizationId,
    configKey: adminConfigurationKey,
    anchorCivilDate: adminAnchorCivilDate,
  };

  it('shows tenant-scoped versions, effective dates, approval and approver', () => {
    wrap(<ConfigEditor {...baseProps} onPropose={vi.fn()} />);
    expect(
      screen.getByRole('table', { name: adminText('en', 'configurationVersions') }),
    ).toBeTruthy();
    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
      '4',
      '3',
      '2',
      '1',
    ]);
    expect(screen.getAllByText(adminText('en', 'effectiveBadge'))).toHaveLength(1);
    expect(screen.getAllByText(adminText('en', 'approved'))).toHaveLength(3);
    expect(screen.getAllByText(adminText('en', 'pending'))).toHaveLength(1);
    expect(screen.getByText('Synthetic alpha admin A')).toBeTruthy();
    expect(screen.getAllByText(adminText('en', 'unknown'))).toHaveLength(1);
  });

  it('blocks an empty or partial draft and proposes a complete local draft', () => {
    const onPropose = vi.fn();
    wrap(<ConfigEditor {...baseProps} onPropose={onPropose} />);
    const submit = screen.getByRole('button', { name: adminText('en', 'propose') });
    const value = screen.getByLabelText(adminText('en', 'proposedValue'));
    const effectiveFrom = screen.getByLabelText(adminText('en', 'effectiveFrom'));

    fireEvent.click(submit);
    expect(onPropose).not.toHaveBeenCalled();
    fireEvent.change(value, { target: { value: 'Synthetic new value' } });
    fireEvent.click(submit);
    expect(onPropose).not.toHaveBeenCalled();

    fireEvent.change(effectiveFrom, { target: { value: '2026-07-01' } });
    fireEvent.click(submit);
    expect(onPropose).toHaveBeenCalledWith({
      value: 'Synthetic new value',
      effectiveFrom: '2026-07-01',
    });
    expect((value as HTMLInputElement).value).toBe('');
    expect((effectiveFrom as HTMLInputElement).value).toBe('');
  });

  it('rejects a non-calendar effective date with an accessible error and no crash', () => {
    const onPropose = vi.fn();
    wrap(<ConfigEditor {...baseProps} onPropose={onPropose} />);
    fireEvent.change(screen.getByLabelText(adminText('en', 'proposedValue')), {
      target: { value: 'Synthetic new value' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'effectiveFrom')), {
      target: { value: '2026-02-30' },
    });
    const valueInput = screen.getByLabelText(adminText('en', 'proposedValue'));
    const dateInput = screen.getByLabelText(adminText('en', 'effectiveFrom'));
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));
    expect(onPropose).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toBe(adminText('en', 'invalidDraft'));
    expect(dateInput.getAttribute('aria-invalid')).toBe('true');
    expect(dateInput.getAttribute('aria-describedby')).toBe('admin-config-effective-error');
    expect(valueInput.getAttribute('aria-invalid')).toBeNull();

    fireEvent.change(dateInput, { target: { value: '2026-07-01' } });
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));
    expect(onPropose).toHaveBeenCalledTimes(1);
  });

  it('associates an empty-value error with the value field and clears it on edit', () => {
    const onPropose = vi.fn();
    wrap(<ConfigEditor {...baseProps} onPropose={onPropose} />);
    const valueInput = screen.getByLabelText(adminText('en', 'proposedValue'));
    const dateInput = screen.getByLabelText(adminText('en', 'effectiveFrom'));
    fireEvent.change(dateInput, { target: { value: '2026-07-01' } });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));
    expect(valueInput.getAttribute('aria-invalid')).toBe('true');
    expect(valueInput.getAttribute('aria-describedby')).toBe('admin-config-value-error');
    expect(dateInput.getAttribute('aria-invalid')).toBeNull();
    expect(screen.getByRole('alert').textContent).toBe(adminText('en', 'invalidDraft'));

    fireEvent.change(valueInput, { target: { value: 'Synthetic corrected value' } });
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));
    expect(onPropose).toHaveBeenCalledTimes(1);
  });

  it('shows a defined empty state when the tenant has no versions', () => {
    wrap(<ConfigEditor {...baseProps} entries={[]} onPropose={vi.fn()} />);
    expect(screen.getByText(adminText('en', 'noVersions'))).toBeTruthy();
  });

  it('renders Arabic labels and dates under RTL', () => {
    wrap(<ConfigEditor {...baseProps} onPropose={vi.fn()} />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(
      screen.getByRole('table', { name: adminText('ar', 'configurationVersions') }),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: adminText('ar', 'propose') })).toBeTruthy();
  });
});

describe('StaffHistoryView', () => {
  it('shows append-only events and current roles for an active subject', () => {
    wrap(
      <StaffHistoryView
        events={syntheticStaffEvents}
        organizationId={alphaOrganizationId}
        subject="synthetic.reception"
      />,
    );
    expect(screen.getByRole('table', { name: adminText('en', 'staffHistory') })).toBeTruthy();
    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual(['1', '2']);
    expect(screen.getAllByText(adminText('en', 'notApplicable')).length).toBeGreaterThan(0);
    expect(screen.getAllByText(adminText('en', 'eventInvited'))).toHaveLength(1);
    expect(screen.getAllByText(adminText('en', 'eventRoleGranted'))).toHaveLength(1);
    expect(screen.queryByText(adminText('en', 'eventDeactivated'))).toBeNull();
    expect(screen.getAllByText('reception').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Synthetic alpha admin A').length).toBeGreaterThan(0);
    expect(screen.getByText(/Current roles/)).toBeTruthy();
  });

  it('shows deactivation as its own event and clears current roles', () => {
    wrap(
      <StaffHistoryView
        events={syntheticStaffEvents}
        organizationId={betaOrganizationId}
        subject="synthetic.reception"
      />,
    );
    expect(screen.getAllByText(adminText('en', 'eventDeactivated')).length).toBeGreaterThan(0);
    expect(screen.getByText('Synthetic tenant offboarding')).toBeTruthy();
    expect(screen.getByText(adminText('en', 'noRoles'))).toBeTruthy();
  });

  it('shows the grantor only for role grants and renders effective UTC instants', () => {
    wrap(
      <StaffHistoryView
        events={syntheticStaffEvents}
        organizationId={alphaOrganizationId}
        subject="synthetic.reception"
      />,
    );
    const rows = screen.getAllByRole('row');
    const invited = must(
      rows.find((row) => within(row).queryByRole('rowheader')?.textContent === '1'),
    );
    const granted = must(
      rows.find((row) => within(row).queryByRole('rowheader')?.textContent === '2'),
    );
    // The invited row has no grantor; the role-grant row names its grantor.
    expect(within(invited).queryByText('Synthetic alpha admin A')).toBeTruthy();
    expect(within(invited).getAllByText(adminText('en', 'notApplicable')).length).toBeGreaterThan(
      0,
    );
    expect(within(granted).getAllByText('Synthetic alpha admin A').length).toBeGreaterThan(0);
    expect(within(granted).getByText('reception')).toBeTruthy();
    // OPS-02: the append-only event carries an effective instant in UTC.
    expect(granted.querySelector('time[datetime="2026-01-06T08:00:00Z"]')).toBeTruthy();
  });

  it('shows an empty state for an unknown subject', () => {
    wrap(
      <StaffHistoryView
        events={syntheticStaffEvents}
        organizationId={alphaOrganizationId}
        subject="missing.subject"
      />,
    );
    expect(screen.getByText(adminText('en', 'noHistory'))).toBeTruthy();
  });

  it('uses Arabic labels under RTL', () => {
    wrap(
      <StaffHistoryView
        events={syntheticStaffEvents}
        organizationId={alphaOrganizationId}
        subject="synthetic.reception"
      />,
      'ar',
    );
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('table', { name: adminText('ar', 'staffHistory') })).toBeTruthy();
  });
});

describe('AuditEventList', () => {
  it('shows every required audit field and a verified badge for a valid chain', () => {
    wrap(<AuditEventList events={syntheticAuditEvents} organizationId={alphaOrganizationId} />);
    expect(screen.getByRole('table', { name: adminText('en', 'auditEvents') })).toBeTruthy();
    expect(screen.getByText(adminText('en', 'chainVerified'))).toBeTruthy();
    expect(screen.getByText('synthetic-correlation-alpha-1')).toBeTruthy();
    expect(screen.getByText('Synthetic audit query')).toBeTruthy();
    expect(screen.getByText('organization:alpha')).toBeTruthy();
    expect(screen.getByText(adminText('en', 'outcomeDenied'))).toBeTruthy();
    // ADM-02: the actor is a distinct required column, not just fixture data.
    expect(screen.getByText('Synthetic alpha privacy auditor')).toBeTruthy();
    expect(screen.getByText('Synthetic alpha reception')).toBeTruthy();
    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
      '1',
      '2',
      '3',
    ]);
  });

  it('shows a broken badge for the tampered tenant chain', () => {
    wrap(<AuditEventList events={syntheticAuditEvents} organizationId={betaOrganizationId} />);
    expect(screen.getByText(adminText('en', 'chainNotVerified'))).toBeTruthy();
    expect(screen.getByText(adminText('en', 'chainBrokenHint'))).toBeTruthy();
    expect(screen.queryByText('synthetic-correlation-alpha-1')).toBeNull();
  });

  it('shows a neutral chain verdict instead of a tamper claim for an empty chain', () => {
    wrap(<AuditEventList events={[]} organizationId={alphaOrganizationId} />);
    expect(screen.getByText(adminText('en', 'noEvents'))).toBeTruthy();
    expect(screen.getByText(adminText('en', 'chainNotApplicable'))).toBeTruthy();
    expect(screen.queryByText(adminText('en', 'chainNotVerified'))).toBeNull();
    expect(screen.queryByText(adminText('en', 'chainBrokenHint'))).toBeNull();
  });

  it('uses Arabic labels under RTL', () => {
    wrap(
      <AuditEventList events={syntheticAuditEvents} organizationId={alphaOrganizationId} />,
      'ar',
    );
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText(adminText('ar', 'chainVerified'))).toBeTruthy();
  });
});

describe('ConfigEditor real-API adaptations', () => {
  const baseProps = {
    entries: syntheticConfigurations,
    organizationId: alphaOrganizationId,
    configKey: adminConfigurationKey,
    anchorCivilDate: adminAnchorCivilDate,
    onPropose: vi.fn(),
  };

  it('offers an approve trigger only when the owner supplies one', () => {
    const onApprove = vi.fn();
    wrap(<ConfigEditor {...baseProps} onApprove={onApprove} />);
    const pendingRow = screen
      .getAllByRole('row')
      .find((row) => within(row).queryByText(adminText('en', 'pending')));
    fireEvent.click(
      within(must(pendingRow)).getByRole('button', { name: adminText('en', 'approve') }),
    );
    expect(onApprove).toHaveBeenCalledWith(3);
  });

  it('keeps the typed draft when a propose command reports failure', () => {
    wrap(<ConfigEditor {...baseProps} onPropose={() => false} />);
    fireEvent.change(screen.getByLabelText(adminText('en', 'proposedValue')), {
      target: { value: 'Synthetic retained value' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'effectiveFrom')), {
      target: { value: '2026-07-01' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));
    expect(screen.getByLabelText<HTMLInputElement>(adminText('en', 'proposedValue')).value).toBe(
      'Synthetic retained value',
    );
  });

  it('keeps the typed draft when an asynchronous propose command resolves to false', async () => {
    wrap(<ConfigEditor {...baseProps} onPropose={() => Promise.resolve(false)} />);
    fireEvent.change(screen.getByLabelText(adminText('en', 'proposedValue')), {
      target: { value: 'Synthetic retained draft' },
    });
    fireEvent.change(screen.getByLabelText(adminText('en', 'effectiveFrom')), {
      target: { value: '2026-07-01' },
    });
    fireEvent.click(screen.getByRole('button', { name: adminText('en', 'propose') }));
    await waitFor(() =>
      expect(screen.getByLabelText<HTMLInputElement>(adminText('en', 'proposedValue')).value).toBe(
        'Synthetic retained draft',
      ),
    );
  });

  it('labels a version with no effective instant as not applicable', () => {
    wrap(
      <ConfigEditor
        {...baseProps}
        entries={[
          {
            organizationId: alphaOrganizationId,
            key: adminConfigurationKey,
            facilityCode: 'MAIN',
            version: 1,
            value: 'Synthetic immediate value',
            effectiveFrom: '',
            approved: true,
          },
        ]}
      />,
    );
    expect(screen.getByText(adminText('en', 'notApplicable'))).toBeTruthy();
    expect(screen.queryByRole('time')).toBeNull();
  });
});

describe('AuditEventList real-API adaptations', () => {
  const events = [
    {
      organizationId: alphaOrganizationId,
      sequence: 1,
      actor: 'Synthetic actor',
      purpose: 'Synthetic purpose',
      scope: 'Synthetic scope',
      correlationId: 'synthetic-corr',
      action: 'CONFIG_CREATE',
      resourceId: 'synthetic-config',
      outcome: 'SUCCESS' as const,
      occurredAt: '2026-03-08T06:30:00Z',
      previousHash: '',
      hash: '',
    },
    {
      organizationId: alphaOrganizationId,
      sequence: 2,
      actor: 'Synthetic actor',
      purpose: 'Synthetic purpose',
      scope: 'Synthetic scope',
      correlationId: 'synthetic-corr-2',
      action: 'CONFIG_APPROVE',
      resourceId: 'synthetic-config',
      outcome: 'NOT_FOUND' as const,
      occurredAt: '',
      previousHash: '',
      hash: '',
    },
  ];

  it('omits a fabricated chain verdict and renders the facility zone', () => {
    wrap(
      <AuditEventList
        events={events}
        organizationId={alphaOrganizationId}
        showChain={false}
        timeZone="America/New_York"
      />,
    );
    expect(screen.queryByTestId('chain-badge')).toBeNull();
    expect(
      screen.getByText(formatInstant('2026-03-08T06:30:00Z', 'America/New_York', 'en')),
    ).toBeTruthy();
    expect(screen.getByText(adminText('en', 'unknown'))).toBeTruthy();
  });

  it('reveals the UTC instant on focus and names the other outcomes', () => {
    wrap(<AuditEventList events={events} organizationId={alphaOrganizationId} showChain={false} />);
    const time = screen.getByText(formatInstant('2026-03-08T06:30:00Z', 'UTC', 'en'));
    fireEvent.focus(time);
    expect(
      screen.getByText(`UTC: ${formatInstant('2026-03-08T06:30:00Z', 'UTC', 'en')}`),
    ).toBeTruthy();
    expect(time.getAttribute('aria-describedby')).toBe('audit-utc-1');
    expect(screen.getByText(adminText('en', 'outcomeNotFound'))).toBeTruthy();
    fireEvent.blur(time);
    expect(
      screen.queryByText(`UTC: ${formatInstant('2026-03-08T06:30:00Z', 'UTC', 'en')}`),
    ).toBeNull();
  });
});

describe('RTL-safe layout', () => {
  it('uses logical styling properties in every admin component', () => {
    const featureDir = resolve(dirname(fileURLToPath(import.meta.url)));
    const componentFiles = readdirSync(featureDir).filter(
      (name) => name.endsWith('.tsx') && !name.includes('.test.'),
    );
    expect(componentFiles.length).toBeGreaterThan(0);
    for (const name of componentFiles) {
      const source = readFileSync(`${featureDir}/${name}`, 'utf8');
      expect(source).not.toMatch(
        /margin(Left|Right)|padding(Left|Right)|border(Left|Right)|textAlign:\s*['"](?:left|right)['"]/,
      );
    }
  });
});
