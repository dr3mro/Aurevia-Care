import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { emptyReport, entererSubject, nonCriticalReport, verifierSubject } from './fixtures';
import { ResultEntryPage } from './ResultEntryPage';

function renderPage(
  options: { locale?: Locale; currentUser?: string; report?: typeof nonCriticalReport } = {},
) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={options.locale ?? 'en'}>
        <ResultEntryPage currentUser={options.currentUser} report={options.report} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

describe('LAB-02 result-entry page', () => {
  it('loads the synthetic report, keeps drafts and announces a successful verification', () => {
    renderPage();
    const input = screen.getByLabelText<HTMLInputElement>('Result value for Hemoglobin');
    expect(input.value).toBe('7.4');

    fireEvent.change(input, { target: { value: '7.9' } });
    expect(screen.getByLabelText<HTMLInputElement>('Result value for Hemoglobin').value).toBe(
      '7.9',
    );
    expect(screen.queryByTestId('verification-status')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Verify and prepare release' }));
    expect(screen.getByTestId('verification-status').textContent).toContain(
      `Verified by ${verifierSubject}. The summary is ready for release.`,
    );
  });

  it('clears a previous success when a typed value changes so the status stays truthful', () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'Verify and prepare release' }));
    expect(screen.getByTestId('verification-status')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('Result value for Sodium'), {
      target: { value: '140' },
    });
    expect(screen.queryByTestId('verification-status')).toBeNull();
    expect(screen.getByLabelText<HTMLInputElement>('Result value for Sodium').value).toBe('140');
  });

  it('keeps typed work and blocks verification when the signed-in user is the enterer', () => {
    renderPage({ currentUser: entererSubject });
    fireEvent.change(screen.getByLabelText('Result value for Potassium'), {
      target: { value: '6.9' },
    });
    const button = screen.getByRole<HTMLButtonElement>('button', {
      name: 'Verify and prepare release',
    });
    expect(button.disabled).toBe(true);
    expect(screen.getByTestId('verify-blocked')).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>('Result value for Potassium').value).toBe('6.9');
    expect(screen.queryByTestId('verification-status')).toBeNull();
  });

  it('renders an explicit empty state for a report with no observations', () => {
    renderPage({ report: emptyReport });
    expect(screen.getByRole('status').textContent).toContain('Nothing to show');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('uses a supplied report without a critical observation', () => {
    renderPage({ report: nonCriticalReport });
    expect(screen.queryByTestId('critical-notification-step')).toBeNull();
    expect(screen.getByText('3 observations will be released')).toBeTruthy();
  });

  it('localizes the page to Arabic', () => {
    renderPage({ locale: 'ar' });
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText('إدخال نتائج المختبر والتحقق منها')).toBeTruthy();
  });
});
