import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { ReleaseState } from './domain';
import {
  correctorSubject,
  emptyRevision,
  proposedChange,
  withdrawalDetail,
  WITHDRAWN_AT,
} from './fixtures';
import { ReleasePage } from './ReleasePage';

function renderPage(
  options: {
    locale?: Locale;
    state?: ReleaseState;
    withdrawal?: typeof withdrawalDetail | null;
    after?: typeof emptyRevision;
    actor?: string;
    withdrawalAt?: string;
  } = {},
) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={options.locale ?? 'en'}>
        <ReleasePage
          state={options.state}
          withdrawal={options.withdrawal}
          after={options.after}
          actor={options.actor}
          withdrawalAt={options.withdrawalAt}
        />
      </I18nProvider>
    </ThemeProvider>,
  );
}

describe('LAB-02 release page', () => {
  it('loads the synthetic revisions and prefills the proposed change', () => {
    renderPage();
    expect(screen.getByText('Laboratory report release and corrections')).toBeTruthy();
    expect(screen.getByLabelText<HTMLTextAreaElement>('What changed').value).toBe(proposedChange);
    expect(
      screen.getByLabelText<HTMLTextAreaElement>('Reason for correction (required)').value,
    ).toBe('');
    expect(
      screen.getByRole<HTMLButtonElement>('button', { name: 'Record correction' }).disabled,
    ).toBe(true);
  });

  it('records a correction with a reason and announces who recorded it', () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('Reason for correction (required)'), {
      target: { value: 'Transcription error' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Record correction' }));
    expect(screen.getByTestId('release-state').textContent).toContain('Corrected');
    expect(screen.getByTestId('correction-status').textContent).toBe(
      `Corrected by ${correctorSubject}. Reason: Transcription error`,
    );
  });

  it('keeps typed work and clears a stale success when the correction changes', () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('What changed'), {
      target: { value: 'My typed change' },
    });
    fireEvent.change(screen.getByLabelText('Reason for correction (required)'), {
      target: { value: 'Transcription error' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Record correction' }));
    expect(screen.getByTestId('correction-status')).toBeTruthy();

    fireEvent.change(screen.getByLabelText('What changed'), {
      target: { value: 'My edited change' },
    });
    expect(screen.queryByTestId('correction-status')).toBeNull();
    expect(screen.getByLabelText<HTMLTextAreaElement>('What changed').value).toBe(
      'My edited change',
    );
    expect(
      screen.getByLabelText<HTMLTextAreaElement>('Reason for correction (required)').value,
    ).toBe('Transcription error');
  });

  it('withdraws a report with a reason and shows the withdrawal state', () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('Reason for withdrawal (required)'), {
      target: { value: 'Wrong accession' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Withdraw report' }));
    expect(screen.getByTestId('withdrawn-notice').textContent).toContain('Wrong accession');
    expect(screen.getByTestId('withdrawn-notice').textContent).toContain(correctorSubject);
    expect(
      screen.getByTestId('withdrawn-notice').querySelector('time')?.getAttribute('datetime'),
    ).toBe(WITHDRAWN_AT);
    expect(screen.getByTestId('release-state').textContent).toContain('Withdrawn');
    expect(screen.getByTestId('release-status-patient').textContent).toContain('Not visible');
    expect(screen.queryByRole('button', { name: 'Withdraw report' })).toBeNull();
  });

  it('uses supplied state, actor, instant and withdrawal detail', () => {
    renderPage({
      state: 'withdrawn',
      withdrawal: withdrawalDetail,
      actor: 'synthetic.lab.other',
      withdrawalAt: '2026-11-21T09:00:00Z',
    });
    expect(screen.getByTestId('withdrawn-notice').textContent).toContain(withdrawalDetail.reason);
    expect(screen.getByTestId('release-state').textContent).toContain('Withdrawn');
  });

  it('renders an explicit empty state when the revision has no observations', () => {
    renderPage({ after: emptyRevision });
    expect(screen.getByRole('status').textContent).toContain('Nothing to show');
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('localizes the page to Arabic', () => {
    renderPage({ locale: 'ar' });
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText('إصدار تقارير المختبر والتصحيحات')).toBeTruthy();
  });
});
