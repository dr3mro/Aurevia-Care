import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import { alphaOrganizationId, betaOrganizationId, syntheticCharts } from './fixtures';
import { chartText } from './messages';
import { ChartScreen } from './ChartScreen';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function screenFor(organizationId: string, locale: Locale = 'en') {
  return wrap(<ChartScreen charts={syntheticCharts} organizationId={organizationId} />, locale);
}

describe('ChartScreen', () => {
  it('shows only the active tenant and a read-only signed revision with history', () => {
    screenFor(alphaOrganizationId);
    expect(screen.getAllByText('Synthetic Layla').length).toBeGreaterThan(0);
    expect(screen.queryByText('Synthetic Reem')).toBeNull();
    expect(
      screen.getByRole('heading', { level: 2, name: chartText('en', 'chart') }),
    ).toBeTruthy();
    expect(
      (screen.getByRole('textbox', { name: chartText('en', 'plan') }) as HTMLTextAreaElement).readOnly,
    ).toBe(true);
    expect(screen.getByRole('button', { name: chartText('en', 'amend') })).toBeTruthy();
    expect(screen.getByRole('list', { name: chartText('en', 'history') })).toBeTruthy();
    expect(screen.getByTestId('chart-status').textContent).toBe('');
  });

  it('signs a tenant-scoped draft only after attestation', () => {
    screenFor(betaOrganizationId);
    expect(screen.getAllByText('Synthetic Reem').length).toBeGreaterThan(0);
    expect(screen.queryByText('Synthetic Layla')).toBeNull();
    const sign = screen.getByRole('button', { name: chartText('en', 'sign') }) as HTMLButtonElement;
    expect(sign.disabled).toBe(true);
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    fireEvent.click(sign);
    expect(screen.getByTestId('chart-status').textContent).toContain(chartText('en', 'signedNotice'));
    expect(
      (screen.getByRole('textbox', { name: chartText('en', 'plan') }) as HTMLTextAreaElement).readOnly,
    ).toBe(true);
    expect(screen.queryByRole('button', { name: chartText('en', 'sign') })).toBeNull();
  });

  it('edits a draft, creates an amendment and supersedes the source revision', () => {
    screenFor(betaOrganizationId);
    fireEvent.change(screen.getByRole('textbox', { name: chartText('en', 'assessment') }), {
      target: { value: 'Synthetic amended assessment' },
    });
    expect(screen.getByTestId('chart-status').textContent).toContain(chartText('en', 'updateNotice'));
    fireEvent.click(screen.getByRole('checkbox', { name: chartText('en', 'attestation') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'sign') }));
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'amend') }));
    expect(screen.getByTestId('chart-status').textContent).toContain(chartText('en', 'amendNotice'));
    expect(screen.getByText(chartText('en', 'noteAmendment'))).toBeTruthy();
    expect(
      (screen.getByRole('textbox', { name: chartText('en', 'plan') }) as HTMLTextAreaElement).readOnly,
    ).toBe(false);
    expect(screen.queryByRole('button', { name: chartText('en', 'amend') })).toBeNull();
  });

  it('records entered-in-error while preserving the prior revision', () => {
    screenFor(alphaOrganizationId);
    const history = screen.getByRole('list', { name: chartText('en', 'history') });
    expect(within(history).getAllByRole('listitem')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'markEnteredInError') }));
    expect(screen.getByRole('alert').textContent).toContain(chartText('en', 'enteredInError'));
    expect(screen.getByTestId('chart-status').textContent).toContain(
      chartText('en', 'enterErrorNotice'),
    );
    expect(screen.queryByRole('button', { name: chartText('en', 'amend') })).toBeNull();
    expect(within(history).getAllByRole('listitem')).toHaveLength(1);
  });

  it('voids and rotates only the selected document association', () => {
    screenFor(alphaOrganizationId);
    const table = screen.getByRole('table', { name: chartText('en', 'documentList') });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(5);
    const liveRow = rows[1]!;
    fireEvent.click(within(liveRow).getByRole('button', { name: chartText('en', 'rotate') }));
    expect(screen.getByTestId('chart-status').textContent).toContain(chartText('en', 'rotateNotice'));
    fireEvent.click(within(liveRow).getByRole('button', { name: chartText('en', 'void') }));
    expect(screen.getByTestId('chart-status').textContent).toContain(chartText('en', 'voidNotice'));
    expect(
      within(liveRow).getByText(
        (content, element) =>
          element?.tagName === 'TD' && content.startsWith(chartText('en', 'documentVoided')),
      ),
    ).toBeTruthy();
    expect(
      (within(liveRow).getByRole('button', { name: chartText('en', 'void') }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });

  it('resets local state when the active tenant changes', () => {
    const { rerender } = screenFor(alphaOrganizationId);
    fireEvent.click(screen.getByRole('button', { name: chartText('en', 'markEnteredInError') }));
    expect(screen.getByRole('alert')).toBeTruthy();

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <ChartScreen charts={syntheticCharts} organizationId={betaOrganizationId} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getAllByText('Synthetic Reem').length).toBeGreaterThan(0);
    expect(screen.queryByText('Synthetic Layla')).toBeNull();
    expect(screen.getByRole('button', { name: chartText('en', 'sign') })).toBeTruthy();
    expect(screen.getByTestId('chart-status').textContent).toBe('');
  });

  it('renders bilingual RTL copy and local names under the Arabic locale', () => {
    screenFor(alphaOrganizationId, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(
      screen.getByRole('heading', { level: 2, name: chartText('ar', 'chart') }),
    ).toBeTruthy();
    expect(screen.getByRole('table', { name: chartText('ar', 'vitals') })).toBeTruthy();
    expect(screen.getAllByText('ليلى تجريبية')[0]?.getAttribute('dir')).toBe('auto');
    expect(screen.getByRole('button', { name: chartText('ar', 'amend') })).toBeTruthy();
  });

  it('shows a concealment-safe empty state when no chart is visible', () => {
    wrap(<ChartScreen charts={syntheticCharts} organizationId="unknown" />);
    expect(screen.getByText(chartText('en', 'noChart'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('shows a note empty state when the visible chart has no revisions', () => {
    const chartWithoutNotes = { ...syntheticCharts[0]!, revisions: [] };
    wrap(<ChartScreen charts={[chartWithoutNotes]} organizationId={alphaOrganizationId} />);
    expect(screen.getByText(chartText('en', 'noRevisions'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: chartText('en', 'amend') })).toBeNull();
  });
});
