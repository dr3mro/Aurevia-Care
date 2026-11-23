import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { ResultsInbox } from './ResultsInbox';
import { sampleInboxRows } from './fixtures';

/** Mount the inbox with a deterministic locale. */
function renderInbox(locale: 'en' | 'ar', rows = sampleInboxRows) {
  render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ResultsInbox rows={rows} locale={locale} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

describe('ResultsInbox', () => {
  it('renders the title, review/delivery note and all six columns', () => {
    renderInbox('en');
    expect(screen.getByRole('heading', { name: 'Inbox' })).toBeTruthy();
    expect(screen.getByText('Clinical review state is separate from delivery state')).toBeTruthy();
    const table = screen.getByRole('table');
    for (const name of [
      'Patient and MRN',
      'Report and date',
      'Source',
      'Kind',
      'Review',
      'Owner',
    ]) {
      expect(within(table).getByRole('columnheader', { name })).toBeTruthy();
    }
  });

  it('shows patient identity, kind wording and review state in each row', () => {
    renderInbox('en');
    expect(screen.getByText(/Nadia Farouk/)).toBeTruthy();
    expect(screen.getByText(/MRN-SYN-1138/)).toBeTruthy();
    expect(screen.getAllByText(/Final/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Corrected/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Critical/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('Unreviewed').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Action pending').length).toBeGreaterThan(0);
  });

  it('renders the defined empty state when there are no rows', () => {
    renderInbox('en', []);
    expect(screen.getByText('Nothing to show in this view.')).toBeTruthy();
  });

  it('renders the Arabic headers under an RTL locale', () => {
    renderInbox('ar');
    expect(screen.getByRole('columnheader', { name: 'المريض ورقم الملف' })).toBeTruthy();
  });
});
