import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { PartnerBoardPage } from './PartnerBoardPage';

/** Render the page through the real providers the shell mounts. */
function renderPage(locale: 'en' | 'ar') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <PartnerBoardPage />
      </I18nProvider>
    </ThemeProvider>,
  );
}

describe('PartnerBoardPage', () => {
  it('renders all three synthetic partner boards in English', () => {
    renderPage('en');
    expect(screen.getByText('Partner interface and outage board')).toBeTruthy();
    expect(screen.getAllByRole('region', { name: /Partner board:/ })).toHaveLength(3);
    expect(screen.getByText('Waiting: 3')).toBeTruthy();
    expect(document.documentElement.dir).toBe('ltr');
    expect(document.documentElement.lang).toBe('en');
  });

  it('mirrors Arabic onto the document and renders the translated board', () => {
    renderPage('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByText('لوحة واجهات الشركاء وانقطاع الخدمة')).toBeTruthy();
    expect(screen.getAllByRole('region', { name: /لوحة الشريك:/ })).toHaveLength(3);
  });
});
