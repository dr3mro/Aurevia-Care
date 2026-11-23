import { fireEvent, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { CorrectionValues, ReleaseState } from './domain';
import {
  correctedRevision,
  identicalRevision,
  originalRevision,
  proposedChange,
  withdrawalDetail,
} from './fixtures';
import { ReleaseScreen } from './ReleaseScreen';

/** Every screen test needs the theme tokens and a locale-owning provider. */
function renderWithLocale(ui: ReactNode, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function renderScreen(
  options: {
    locale?: Locale;
    state?: ReleaseState;
    withdrawal?: typeof withdrawalDetail | null;
    correction?: CorrectionValues;
    withdrawReason?: string;
    correctionNotice?: string | null;
    before?: typeof originalRevision;
    after?: typeof correctedRevision;
  } = {},
) {
  const onCorrectionChange = vi.fn();
  const onWithdrawReasonChange = vi.fn();
  const onCorrect = vi.fn();
  const onWithdraw = vi.fn();
  renderWithLocale(
    <ReleaseScreen
      before={options.before ?? originalRevision}
      after={options.after ?? correctedRevision}
      state={options.state ?? 'final'}
      withdrawal={options.withdrawal === undefined ? null : options.withdrawal}
      locale={options.locale ?? 'en'}
      correction={options.correction ?? { changeSummary: proposedChange, reason: '' }}
      withdrawReason={options.withdrawReason ?? ''}
      correctionNotice={options.correctionNotice ?? null}
      onCorrectionChange={onCorrectionChange}
      onWithdrawReasonChange={onWithdrawReasonChange}
      onCorrect={onCorrect}
      onWithdraw={onWithdraw}
    />,
    options.locale ?? 'en',
  );
  return { onCorrectionChange, onWithdrawReasonChange, onCorrect, onWithdraw };
}

describe('LAB-02 side-by-side revision comparison', () => {
  it('shows old and new readings for a changed observation with a word and a glyph', () => {
    renderScreen();
    const table = screen.getByRole('table', { name: /LAB-1042/ });
    const row = within(table).getByTestId('diff-hemoglobin');
    expect(row.getAttribute('data-change')).toBe('changed');
    const oldReading = within(row).getByText(/13\.2/);
    expect(oldReading.textContent).toBe('13.2\u00a0g/dL');
    expect(oldReading.getAttribute('dir')).toBe('ltr');
    expect(within(row).getByText(/7\.4/).getAttribute('dir')).toBe('ltr');
    expect(within(row).getByText('Changed')).toBeTruthy();
  });

  it('distinguishes unchanged, removed and added observations in words', () => {
    renderScreen();
    const table = screen.getByRole('table', { name: /LAB-1042/ });
    expect(within(table).getByTestId('diff-potassium').getAttribute('data-change')).toBe(
      'unchanged',
    );
    expect(within(table).getByTestId('diff-sodium').getAttribute('data-change')).toBe('removed');
    expect(within(table).getByTestId('diff-vitamin-d').getAttribute('data-change')).toBe('added');
    expect(within(table).getAllByText('Not present in this revision')).toHaveLength(2);
    expect(within(table).getByText('Unchanged')).toBeTruthy();
    expect(within(table).getByText('Removed')).toBeTruthy();
    expect(within(table).getByText('Added')).toBeTruthy();
    expect(within(table).getAllByText(/^[=✎+−]$/)).toHaveLength(4);
  });

  it('renders an explicit state when the two revisions have no differences', () => {
    renderScreen({ before: originalRevision, after: identicalRevision });
    expect(screen.getByText('No observations changed between these revisions.')).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });
});

describe('LAB-02 correction form', () => {
  it('requires a reason and keeps the proposed change visible', () => {
    renderScreen();
    const button = screen.getByRole<HTMLButtonElement>('button', { name: 'Record correction' });
    expect(button.disabled).toBe(true);
    expect(screen.getByTestId('correction-reason-required').textContent).toContain(
      'State why this correction is needed before recording it.',
    );
    const summary = screen.getByLabelText<HTMLTextAreaElement>('What changed');
    expect(summary.value).toBe(proposedChange);
    expect(summary.getAttribute('lang')).toBe('en');
  });

  it('sends the edited change summary and reason without losing either field', () => {
    const { onCorrectionChange } = renderScreen();
    fireEvent.change(screen.getByLabelText('What changed'), {
      target: { value: 'Hemoglobin value corrected' },
    });
    expect(onCorrectionChange).toHaveBeenCalledWith({
      changeSummary: 'Hemoglobin value corrected',
      reason: '',
    });
    fireEvent.change(screen.getByLabelText('Reason for correction (required)'), {
      target: { value: 'Transcription error' },
    });
    expect(onCorrectionChange).toHaveBeenCalledWith({
      changeSummary: proposedChange,
      reason: 'Transcription error',
    });
  });

  it('lets a named reason be recorded and announces the outcome', () => {
    const { onCorrect } = renderScreen({
      correction: { changeSummary: proposedChange, reason: 'Transcription error' },
      correctionNotice: 'Corrected by synthetic.lab.supervisor. Reason: Transcription error',
    });
    expect(screen.queryByTestId('correction-reason-required')).toBeNull();
    const button = screen.getByRole<HTMLButtonElement>('button', { name: 'Record correction' });
    expect(button.disabled).toBe(false);
    fireEvent.click(button);
    expect(onCorrect).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('correction-status').textContent).toContain(
      'Corrected by synthetic.lab.supervisor. Reason: Transcription error',
    );
  });

  it('hides the outcome until a correction is recorded', () => {
    renderScreen();
    expect(screen.queryByTestId('correction-status')).toBeNull();
  });
});

describe('LAB-02 withdrawal action', () => {
  it('requires a reason and explains the consequence', () => {
    renderScreen();
    const button = screen.getByRole<HTMLButtonElement>('button', { name: 'Withdraw report' });
    expect(button.disabled).toBe(true);
    expect(screen.getByTestId('withdraw-reason-required').textContent).toContain(
      'State why this report is being withdrawn.',
    );
    expect(
      screen.getByText(/Withdrawing removes the report from both clinician and patient view/),
    ).toBeTruthy();
  });

  it('sends the typed withdrawal reason', () => {
    const { onWithdrawReasonChange, onWithdraw } = renderScreen({
      withdrawReason: 'Wrong accession',
    });
    fireEvent.change(screen.getByLabelText('Reason for withdrawal (required)'), {
      target: { value: 'Wrong specimen' },
    });
    expect(onWithdrawReasonChange).toHaveBeenCalledWith('Wrong specimen');
    const button = screen.getByRole<HTMLButtonElement>('button', { name: 'Withdraw report' });
    expect(button.disabled).toBe(false);
    fireEvent.click(button);
    expect(onWithdraw).toHaveBeenCalledTimes(1);
  });
});

describe('LAB-02 release status for clinician and patient', () => {
  it('shows the state and the visibility for each audience', () => {
    renderScreen({ state: 'corrected' });
    expect(screen.getByTestId('release-state').textContent).toContain('Corrected');
    expect(screen.getByTestId('release-status-clinician').textContent).toContain('Released');
    expect(screen.getByTestId('release-status-patient').textContent).toContain('Released');
  });

  it('withholds a preliminary report from the patient but not the clinician', () => {
    renderScreen({ state: 'preliminary' });
    expect(screen.getByTestId('release-status-clinician').textContent).toContain('Released');
    expect(screen.getByTestId('release-status-patient').textContent).toContain('Not visible');
  });

  it('makes a withdrawn report visible as withdrawn and hides the withdraw action', () => {
    renderScreen({ state: 'withdrawn', withdrawal: withdrawalDetail });
    const notice = screen.getByTestId('withdrawn-notice');
    expect(notice.textContent).toContain('This report was withdrawn and entered in error.');
    expect(notice.textContent).toContain(withdrawalDetail.reason);
    expect(notice.textContent).toContain(withdrawalDetail.actor);
    expect(notice.querySelector('time')?.getAttribute('datetime')).toBe(
      withdrawalDetail.recordedAt,
    );
    expect(screen.queryByRole('button', { name: 'Withdraw report' })).toBeNull();
    expect(screen.getByTestId('release-state').textContent).toContain('Withdrawn');
    expect(screen.getByTestId('release-status-clinician').textContent).toContain('Not visible');
    expect(screen.getByTestId('release-status-patient').textContent).toContain('Not visible');
  });

  it('still shows the withdrawn state when no withdrawal detail was supplied', () => {
    renderScreen({ state: 'withdrawn' });
    expect(screen.queryByTestId('withdrawn-notice')).toBeNull();
    expect(screen.getByTestId('release-state').textContent).toContain('Withdrawn');
  });
});

describe('UX-01 Arabic release screen', () => {
  it('mirrors the document and translates the comparison, forms and status', () => {
    renderScreen({ locale: 'ar' });
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByText('إصدار تقارير المختبر والتصحيحات')).toBeTruthy();
    expect(within(screen.getByRole('table')).getByText('مُغيّر')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'تسجيل التصحيح' })).toBeTruthy();
    expect(screen.getByTestId('release-status-patient').textContent).toContain('تم الإصدار');
    expect(screen.getByText('LAB-1042').getAttribute('dir')).toBe('ltr');
  });

  it('mirrors the withdrawal notice in Arabic', () => {
    renderScreen({ locale: 'ar', state: 'withdrawn', withdrawal: withdrawalDetail });
    expect(screen.getByTestId('withdrawn-notice').textContent).toContain('تم سحب هذا التقرير');
    expect(screen.getByTestId('release-state').textContent).toContain('مُسحوب');
  });
});
