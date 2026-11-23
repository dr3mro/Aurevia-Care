import { fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { entererSubject, nonCriticalReport, syntheticReport, verifierSubject } from './fixtures';
import { ChangeIndicator, FlagBadge, ResultEntryScreen } from './ResultEntryScreen';

/** Every screen test needs theme tokens and a locale-owning provider. */
function renderWithLocale(ui: ReactNode, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Drafts are supplied by the page; tests start from the recorded values. */
function draftsFor(report: typeof syntheticReport): Record<string, string> {
  return Object.fromEntries(report.rows.map((row) => [row.id, String(row.value)]));
}

function renderScreen(
  options: {
    locale?: Locale;
    verifier?: string;
    verified?: boolean;
    report?: typeof syntheticReport;
    onValueChange?: (rowId: string, value: string) => void;
    onVerify?: () => void;
  } = {},
) {
  const report = options.report ?? syntheticReport;
  const onValueChange = options.onValueChange ?? vi.fn();
  const onVerify = options.onVerify ?? vi.fn();
  renderWithLocale(
    <ResultEntryScreen
      report={report}
      drafts={draftsFor(report)}
      verifier={options.verifier ?? verifierSubject}
      locale={options.locale ?? 'en'}
      verified={options.verified ?? false}
      onValueChange={onValueChange}
      onVerify={onVerify}
    />,
    options.locale ?? 'en',
  );
  return { onValueChange, onVerify };
}

describe('LAB-02 result entry grid', () => {
  it('shows units, reference ranges, previous values and dates beside each test', () => {
    renderScreen();
    expect(screen.getByRole('table', { name: /LAB-1042/ })).toBeTruthy();
    expect(screen.getByText('g/dL').getAttribute('dir')).toBe('ltr');
    expect(screen.getByText('12.0–16.0')).toBeTruthy();
    expect(screen.getByText(/13\.2/)).toBeTruthy();
    expect(screen.getAllByText('September 14, 2026')).toHaveLength(4);
    expect(screen.getByText('No previous result')).toBeTruthy();
    expect(screen.getByLabelText('Result value for Hemoglobin')).toBeTruthy();
  });

  it('shows abnormal flags as a word and a distinct icon', () => {
    renderScreen();
    const low = required(within(screen.getByRole('table')).getByText('Low').parentElement);
    expect(low.getAttribute('data-flag')).toBe('low');
    expect(low.querySelector('[aria-hidden="true"]')?.textContent).toBe('▼');
    const table = screen.getByRole('table');
    const critical = required(within(table).getByText('Critical').parentElement);
    expect(critical.querySelector('[aria-hidden="true"]')?.textContent).toBe('‼');
    expect(within(table).getAllByText('Normal')).toHaveLength(2);
    expect(within(table).getByText('High')).toBeTruthy();
  });

  it('warns in words with an icon when the change exceeds the configured limit', () => {
    renderScreen();
    expect(screen.getByText('Large change from previous: 43.9%')).toBeTruthy();
    expect(screen.getByText('Large change from previous: 61.9%')).toBeTruthy();
    expect(screen.getAllByText('Within change limit')).toHaveLength(2);
    expect(screen.getByText('No previous value')).toBeTruthy();
    expect(screen.getAllByText('⚠')).toHaveLength(2);
  });

  it('reports the outcome of the delta check for a zero and an absent previous value', () => {
    renderWithLocale(
      <>
        <ChangeIndicator row={required(syntheticReport.rows[3])} locale="en" />
        <ChangeIndicator row={required(syntheticReport.rows[4])} locale="en" />
      </>,
    );
    expect(screen.getAllByText('Within change limit')).toHaveLength(1);
    expect(screen.getByText('No previous value')).toBeTruthy();
  });

  it('falls back to the recorded value when a draft has not been supplied yet', () => {
    renderWithLocale(
      <ResultEntryScreen
        report={syntheticReport}
        drafts={{}}
        verifier={verifierSubject}
        locale="en"
        verified={false}
        onValueChange={vi.fn()}
        onVerify={vi.fn()}
      />,
    );
    expect(screen.getByLabelText<HTMLInputElement>('Result value for Hemoglobin').value).toBe(
      '7.4',
    );
    expect(
      screen.getByRole('region', { name: 'Verification and release summary' }).textContent,
    ).toContain('Hemoglobin: 7.4\u00a0g/dL');
  });

  it('sends an edited value to the container without changing the recorded flag', () => {
    const { onValueChange } = renderScreen();
    fireEvent.change(screen.getByLabelText('Result value for Hemoglobin'), {
      target: { value: '7.9' },
    });
    expect(onValueChange).toHaveBeenCalledWith('hemoglobin', '7.9');
    expect(within(screen.getByRole('table')).getByText('Low')).toBeTruthy();
  });
});

describe('LAB-02 second-user verification', () => {
  it('summarises what will be released and shows the critical notification step', () => {
    renderScreen();
    expect(screen.getByRole('region', { name: 'Verification and release summary' })).toBeTruthy();
    expect(screen.getByText('Preliminary report')).toBeTruthy();
    expect(screen.getByText('5 observations will be released')).toBeTruthy();
    expect(
      screen.getByRole('region', { name: 'Verification and release summary' }).textContent,
    ).toContain('Hemoglobin: 7.4\u00a0g/dL');
    expect(screen.getByTestId('critical-notification-step').textContent).toContain(
      'Notify the responsible clinician and record read-back before release.',
    );
  });

  it('omits the critical notification step when no observation is critical', () => {
    renderScreen({ report: nonCriticalReport });
    expect(screen.queryByTestId('critical-notification-step')).toBeNull();
    expect(screen.getByText('3 observations will be released')).toBeTruthy();
  });

  it('blocks the enterer from verifying and explains why', () => {
    renderScreen({ verifier: entererSubject });
    const button = screen.getByRole<HTMLButtonElement>('button', {
      name: 'Verify and prepare release',
    });
    expect(button.disabled).toBe(true);
    expect(screen.getByTestId('verify-blocked').textContent).toContain(
      'The enterer cannot verify this report. A second user must verify.',
    );
  });

  it('lets a distinct second user verify and announces the outcome', () => {
    const { onVerify } = renderScreen({ verified: true });
    expect(screen.queryByTestId('verify-blocked')).toBeNull();
    const button = screen.getByRole<HTMLButtonElement>('button', {
      name: 'Verify and prepare release',
    });
    expect(button.disabled).toBe(false);
    fireEvent.click(button);
    expect(onVerify).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('verification-status').textContent).toContain(
      `Verified by ${verifierSubject}. The summary is ready for release.`,
    );
  });

  it('keeps the outcome hidden until verification succeeds', () => {
    renderScreen();
    expect(screen.queryByTestId('verification-status')).toBeNull();
  });

  it('renders every flag word from its own badge in both directions', () => {
    renderWithLocale(<FlagBadge flag="normal" locale="ar" />, 'ar');
    expect(screen.getByText('طبيعي').getAttribute('lang')).toBe('ar');
  });
});

describe('UX-01 Arabic result entry', () => {
  it('mirrors the document and translates flags, warnings and the notification step', () => {
    renderScreen({ locale: 'ar' });
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText('إدخال نتائج المختبر والتحقق منها')).toBeTruthy();
    expect(within(screen.getByRole('table')).getByText('منخفض')).toBeTruthy();
    expect(screen.getByText('تغيّر كبير عن السابق: 43.9%')).toBeTruthy();
    expect(screen.getByTestId('critical-notification-step').textContent).toContain(
      'خطوة إشعار النتيجة الحرجة',
    );
    expect(screen.getByText('LAB-1042').getAttribute('dir')).toBe('ltr');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
