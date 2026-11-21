import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { LabBoardPage } from './LabBoardPage';

/** Render the page through the real providers the shell mounts. */
function renderPage(locale: 'en' | 'ar') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <LabBoardPage />
      </I18nProvider>
    </ThemeProvider>,
  );
}

describe('LabBoardPage', () => {
  it('renders the synthetic lab board with the outage route in English', () => {
    renderPage('en');
    expect(screen.getByText('Laboratory turnaround board')).toBeTruthy();
    expect(screen.getByRole('alert', { name: 'Partner interface outage' })).toBeTruthy();
    expect(screen.getAllByTestId('turnaround-column')).toHaveLength(5);
    expect(document.documentElement.dir).toBe('ltr');
    expect(document.documentElement.lang).toBe('en');
  });

  it('mirrors Arabic onto the document and renders the translated board', () => {
    renderPage('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByText('لوحة زمن إنجاز التحاليل')).toBeTruthy();
    expect(screen.getByRole('alert', { name: 'انقطاع واجهة الشريك' })).toBeTruthy();
  });
});
