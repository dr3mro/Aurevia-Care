import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { EMPTY_POLICY_DRAFT, type PolicyVersion } from './domain';
import {
  alphaOrganizationId,
  policyAnchorCivilDate,
  syntheticReleaseVersions,
  syntheticTemplateVersions,
} from './fixtures';
import { policyText } from './messages';
import { ApprovalActions, VersionHistory } from './VersionHistory';
import { PolicyEditor } from './PolicyEditor';
import { TemplateScreen } from './TemplateScreen';
import { FieldDictionaryScreen } from './FieldDictionaryScreen';
import { ReleasePolicyScreen } from './ReleasePolicyScreen';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Fill the three fields every editor shares. */
function fillCommon(value = 'Synthetic proposed value', date = '2026-07-01') {
  fireEvent.change(screen.getByLabelText(policyText('en', 'proposedValue')), { target: { value } });
  fireEvent.change(screen.getByLabelText(policyText('en', 'effectiveFrom')), { target: { value: date } });
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: policyText('en', 'proposeVersion') }));
}

/** One approved version with an approver and one pending version without one. */
const versions: PolicyVersion[] = syntheticTemplateVersions.filter(
  (row) => row.organizationId === alphaOrganizationId,
);

beforeEach(() => {
  document.documentElement.dir = 'ltr';
  document.documentElement.lang = 'en';
});

describe('VersionHistory', () => {
  it('shows every version with its approval, approver and effective civil date', () => {
    wrap(
      <VersionHistory
        title={policyText('en', 'history')}
        emptyMessage={policyText('en', 'noVersions')}
        versions={versions}
        effectiveVersionNumber={2}
      />,
    );
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('row').length).toBe(6);
    expect(within(table).getByText('Synthetic SOAP fields with structured vitals section')).toBeTruthy();
    expect(within(table).getAllByText(policyText('en', 'approved')).length).toBeGreaterThan(0);
    expect(within(table).getByText(policyText('en', 'pending'))).toBeTruthy();
    expect(within(table).getByText('Synthetic clinical governance B')).toBeTruthy();
    expect(within(table).getByText(policyText('en', 'unknown'))).toBeTruthy();
    expect(within(table).getAllByText(policyText('en', 'effectiveBadge')).length).toBeGreaterThan(0);
    expect(table.querySelector('time')?.getAttribute('datetime')).toBe('2026-01-01');
  });

  it('shows an explicit empty history sentence', () => {
    wrap(
      <VersionHistory
        title={policyText('en', 'history')}
        emptyMessage={policyText('en', 'noVersions')}
        versions={[]}
        effectiveVersionNumber={undefined}
      />,
    );
    expect(screen.getByText(policyText('en', 'noVersions'))).toBeTruthy();
  });

  it('renders the recorded proposal, hold reason and review date for a version', () => {
    const detailed: PolicyVersion = {
      organizationId: alphaOrganizationId,
      key: 'release.hold',
      version: 1,
      value: 'Synthetic held release',
      effectiveFrom: '2026-02-01',
      approval: 'approved',
      approver: 'synthetic-actor-uuid',
      proposedBy: 'Synthetic named approver',
      reason: 'Synthetic specialist review',
      expiry: '2026-05-01',
    };
    wrap(
      <VersionHistory
        title={policyText('en', 'history')}
        emptyMessage={policyText('en', 'noVersions')}
        versions={[
          detailed,
          {
            organizationId: alphaOrganizationId,
            key: 'release.timed',
            version: 7,
            value: 'Synthetic scheduled release',
            effectiveFrom: '2026-04-01',
            approval: 'approved',
            proposedBy: 'Synthetic second approver',
          },
        ]}
        effectiveVersionNumber={1}
      />,
    );
    expect(screen.getAllByText(/Proposed by/).length).toBeGreaterThan(0);
    expect(screen.getByText('Synthetic named approver')).toBeTruthy();
    expect(screen.getByText('Synthetic specialist review')).toBeTruthy();
    expect(screen.getAllByText(/Review by/).length).toBeGreaterThan(0);
    expect(screen.getByText(/Recorded approver/)).toBeTruthy();
  });

  it('renders an undated version without a date and without an effective badge', () => {
    const undated: PolicyVersion = {
      organizationId: alphaOrganizationId,
      key: 'template.soap',
      version: 9,
      value: 'Synthetic undated draft',
      effectiveFrom: '',
      approval: 'pending',
    };
    wrap(
      <VersionHistory
        title={policyText('en', 'history')}
        emptyMessage={policyText('en', 'noVersions')}
        versions={[undated]}
        effectiveVersionNumber={2}
      />,
    );
    expect(screen.getByText(policyText('en', 'notApplicable'))).toBeTruthy();
    expect(screen.queryByText(policyText('en', 'effectiveBadge'))).toBeNull();
  });

  it('renders the history in Arabic with a right-to-left document', async () => {
    wrap(
      <VersionHistory
        title={policyText('ar', 'history')}
        emptyMessage={policyText('ar', 'noVersions')}
        versions={versions}
        effectiveVersionNumber={2}
      />,
      'ar',
    );
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'));
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getAllByText(policyText('ar', 'approved')).length).toBeGreaterThan(0);
    expect(screen.getByText(policyText('ar', 'recordedApprover'))).toBeTruthy();
    expect(screen.getByRole('table').querySelector('bdi')?.getAttribute('dir')).toBe('auto');
  });
});

describe('ApprovalActions', () => {
  it('offers approval only for pending versions', () => {
    const onApprove = vi.fn();
    wrap(<ApprovalActions versions={versions} onApprove={onApprove} />);
    const button = screen.getByRole('button', { name: `${policyText('en', 'approve')} 3` });
    fireEvent.click(button);
    expect(onApprove).toHaveBeenCalledWith(3);
    expect(screen.queryByRole('button', { name: `${policyText('en', 'approve')} 2` })).toBeNull();
  });
});

describe('PolicyEditor', () => {
  const baseProps = {
    title: policyText('en', 'proposeVersion'),
    submitLabel: policyText('en', 'proposeVersion'),
    requiresApprover: true,
    requiresCode: false,
    showReleaseModes: false,
  };

  it('refuses a change without a named approver and keeps what was typed', () => {
    const onSubmit = vi.fn();
    wrap(<PolicyEditor {...baseProps} onSubmit={onSubmit} />);
    fillCommon();
    submit();
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getAllByRole('alert').length).toBeGreaterThan(0);
    expect(screen.getByText(policyText('en', 'approverRequired'))).toBeTruthy();
    expect(screen.getByTestId('policy-editor-status').textContent).toBe(
      policyText('en', 'changeRefused'),
    );
    expect((screen.getByLabelText(policyText('en', 'proposedValue')) as HTMLInputElement).value).toBe(
      'Synthetic proposed value',
    );
  });

  it('sends a validated change and clears the draft on success', async () => {
    const onSubmit = vi.fn().mockResolvedValue(true);
    wrap(<PolicyEditor {...baseProps} onSubmit={onSubmit} />);
    fillCommon();
    fireEvent.change(screen.getByLabelText(policyText('en', 'approverName')), {
      target: { value: 'Synthetic named approver' },
    });
    submit();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith({
      ...EMPTY_POLICY_DRAFT,
      value: 'Synthetic proposed value',
      approver: 'Synthetic named approver',
      effectiveFrom: '2026-07-01',
    });
    await waitFor(() =>
      expect(screen.getByTestId('policy-editor-status').textContent).toBe(
        policyText('en', 'changeSaved'),
      ),
    );
    expect((screen.getByLabelText(policyText('en', 'proposedValue')) as HTMLInputElement).value).toBe('');
  });

  it('keeps the draft when the command reports a conflict', async () => {
    wrap(<PolicyEditor {...baseProps} onSubmit={() => Promise.resolve(false)} />);
    fillCommon();
    fireEvent.change(screen.getByLabelText(policyText('en', 'approverName')), {
      target: { value: 'Synthetic named approver' },
    });
    submit();
    await waitFor(() =>
      expect(screen.getByTestId('policy-editor-status').textContent).toBe(
        policyText('en', 'changeRefused'),
      ),
    );
    expect((screen.getByLabelText(policyText('en', 'proposedValue')) as HTMLInputElement).value).toBe(
      'Synthetic proposed value',
    );
  });

  it('accepts a synchronous successful command', () => {
    const onSubmit = vi.fn().mockReturnValue(undefined);
    wrap(<PolicyEditor {...baseProps} onSubmit={onSubmit} />);
    fillCommon();
    fireEvent.change(screen.getByLabelText(policyText('en', 'approverName')), {
      target: { value: 'Synthetic approver' },
    });
    submit();
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('lists every refused field in one linked summary', () => {
    wrap(<PolicyEditor {...baseProps} requiresCode onSubmit={vi.fn()} />);
    submit();
    expect(screen.getByText(policyText('en', 'codeRequired'))).toBeTruthy();
    expect(screen.getByText(policyText('en', 'valueRequired'))).toBeTruthy();
    expect(screen.getByText(policyText('en', 'approverRequired'))).toBeTruthy();
    expect(screen.getByText(policyText('en', 'effectiveDateRequired'))).toBeTruthy();
    expect(screen.getByRole('link', { name: /Proposed by/ })).toBeTruthy();
  });

  it('clears a field error when the reviewer corrects the field', () => {
    wrap(<PolicyEditor {...baseProps} onSubmit={vi.fn()} />);
    submit();
    expect(screen.getByText(policyText('en', 'approverRequired'))).toBeTruthy();
    fireEvent.change(screen.getByLabelText(policyText('en', 'proposedValue')), {
      target: { value: 'Synthetic value' },
    });
    expect(screen.queryByText(policyText('en', 'valueRequired'))).toBeNull();
  });

  it('keeps the primary button a keyboard-operable native button', () => {
    wrap(<PolicyEditor {...baseProps} onSubmit={vi.fn()} />);
    const button = screen.getByRole('button', { name: policyText('en', 'proposeVersion') });
    expect(button.tagName).toBe('BUTTON');
    expect(button.getAttribute('type')).toBe('submit');
    fireEvent.click(button);
    expect(screen.getByTestId('policy-editor-status').textContent).toBe(
      policyText('en', 'changeRefused'),
    );
  });

  it('shows release modes and reveals reason and expiry only when needed', () => {
    wrap(<PolicyEditor {...baseProps} showReleaseModes onSubmit={vi.fn()} />);
    expect(screen.getByRole('button', { name: policyText('en', 'modeImmediate') }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.queryByLabelText(policyText('en', 'holdReason'))).toBeNull();
    expect(screen.queryByLabelText(policyText('en', 'holdExpiry'))).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'modeTimed') }));
    expect(screen.getByLabelText(policyText('en', 'holdExpiry'))).toBeTruthy();
    expect(screen.queryByLabelText(policyText('en', 'holdReason'))).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: policyText('en', 'modeHold') }));
    expect(screen.getByLabelText(policyText('en', 'holdReason'))).toBeTruthy();
    expect(screen.getByLabelText(policyText('en', 'holdExpiry'))).toBeTruthy();
    expect(screen.getByRole('button', { name: policyText('en', 'modeHold') }).getAttribute('aria-pressed')).toBe('true');
  });
});

describe('policy screens', () => {
  it('shows the template history and its governing form', () => {
    wrap(
      <TemplateScreen
        versions={versions}
        effectiveVersionNumber={2}
        onPropose={vi.fn()}
        onApprove={vi.fn()}
      />,
    );
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getByRole('button', { name: policyText('en', 'proposeVersion') })).toBeTruthy();
    expect(screen.getByRole('button', { name: `${policyText('en', 'approve')} 3` })).toBeTruthy();
  });

  it('shows the dictionary field history and a code-bearing form', () => {
    wrap(
      <FieldDictionaryScreen
        versions={versions}
        effectiveVersionNumber={undefined}
        onDefine={vi.fn()}
      />,
    );
    expect(screen.getByLabelText(policyText('en', 'fieldCode'))).toBeTruthy();
    expect(screen.getByRole('button', { name: policyText('en', 'defineField') })).toBeTruthy();
  });

  it('shows the current patient access and release controls', () => {
    const current = syntheticReleaseVersions.find(
      (row) => row.organizationId === alphaOrganizationId && row.version === 1 && row.key === 'release.default',
    );
    wrap(
      <ReleasePolicyScreen
        organizationId={alphaOrganizationId}
        versions={syntheticReleaseVersions}
        currentVersion={current}
        anchorCivilDate={policyAnchorCivilDate}
        effectiveVersionNumber={1}
        onSet={vi.fn()}
        onApprove={vi.fn()}
      />,
    );
    expect(screen.getByText(policyText('en', 'currentAccess'))).toBeTruthy();
    expect(screen.getByRole('button', { name: policyText('en', 'modeHold') })).toBeTruthy();
    expect(screen.getByText(policyText('en', 'accessReleased'))).toBeTruthy();
  });

  it('shows the hold reason, review date and next scheduled release', () => {
    const held = syntheticReleaseVersions.find(
      (row) =>
        row.organizationId === alphaOrganizationId &&
        row.key === 'release.hold' &&
        row.version === 1,
    );
    wrap(
      <ReleasePolicyScreen
        organizationId={alphaOrganizationId}
        versions={[
          ...syntheticReleaseVersions,
          {
            organizationId: alphaOrganizationId,
            key: 'release.timed',
            version: 7,
            value: 'Synthetic later scheduled release',
            effectiveFrom: '2026-05-01',
            approval: 'approved',
            mode: 'timed' as const,
            expiry: '2026-05-01',
          },
        ]}
        currentVersion={held}
        anchorCivilDate={policyAnchorCivilDate}
        effectiveVersionNumber={1}
        onSet={vi.fn()}
        onApprove={vi.fn()}
      />,
    );
    expect(screen.getAllByText(/Reason/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('Synthetic specialist review not complete').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Review by/).length).toBeGreaterThan(0);
    expect(screen.getByText(/Next scheduled release/)).toBeTruthy();
  });

  it('explains that access is held when no policy is in force', () => {
    wrap(
      <ReleasePolicyScreen
        organizationId={alphaOrganizationId}
        versions={[]}
        currentVersion={undefined}
        anchorCivilDate={policyAnchorCivilDate}
        effectiveVersionNumber={undefined}
        onSet={vi.fn()}
        onApprove={vi.fn()}
      />,
    );
    expect(screen.getByText(policyText('en', 'noCurrentPolicy'))).toBeTruthy();
    expect(screen.getByText(policyText('en', 'noVersions'))).toBeTruthy();
  });

  it('shows the next scheduled release when no policy is in force', () => {
    const future = {
      organizationId: alphaOrganizationId,
      key: 'release.timed',
      version: 4,
      value: 'Synthetic future timed release',
      effectiveFrom: '2026-04-01',
      approval: 'approved' as const,
      mode: 'timed' as const,
      expiry: '2026-04-01',
    };
    wrap(
      <ReleasePolicyScreen
        organizationId={alphaOrganizationId}
        versions={[future]}
        currentVersion={undefined}
        anchorCivilDate={policyAnchorCivilDate}
        effectiveVersionNumber={undefined}
        onSet={vi.fn()}
        onApprove={vi.fn()}
      />,
    );
    expect(screen.getByText(policyText('en', 'noCurrentPolicy'))).toBeTruthy();
    expect(screen.getByText(/Next scheduled release/)).toBeTruthy();
  });

  it('renders a release policy screen in Arabic with RTL direction', async () => {
    wrap(
      <ReleasePolicyScreen
        organizationId={alphaOrganizationId}
        versions={syntheticReleaseVersions}
        currentVersion={undefined}
        anchorCivilDate={policyAnchorCivilDate}
        effectiveVersionNumber={undefined}
        onSet={vi.fn()}
        onApprove={vi.fn()}
      />,
      'ar',
    );
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'));
    expect(screen.getByRole('button', { name: policyText('ar', 'setPolicy') })).toBeTruthy();
    expect(screen.getByText(policyText('ar', 'modeHold'))).toBeTruthy();
  });
});
