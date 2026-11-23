import { render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { PartnerBoard, PartnerBoardOverview } from './PartnerBoardScreen';
import { emptyBoard, imagingBoard, labBoard, pharmacyBoard, syntheticBoards } from './fixtures';

/** Every screen needs the theme tokens and a locale-owning provider. */
function renderWithLocale(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

describe('PartnerBoard', () => {
  it('shows interface health, waiting and stuck counts, and the last check time in English', () => {
    renderWithLocale(<PartnerBoard board={labBoard} locale="en" />);

    expect(screen.getByRole('region', { name: /Partner board:/ })).toBeTruthy();
    expect(screen.getByText(/Interface health/)).toBeTruthy();
    expect(screen.getByText(/Degraded/)).toBeTruthy();
    expect(screen.getByText('Waiting: 3')).toBeTruthy();
    expect(screen.getByText('Stuck: 2')).toBeTruthy();
    expect(
      screen.getByText(
        `Last checked: ${formatInstant(labBoard.lastCheckedAt, labBoard.timeZone, 'en')}`,
      ),
    ).toBeTruthy();
  });

  it('lists the waiting orders with opaque references, how long they waited and worded states', () => {
    renderWithLocale(<PartnerBoard board={labBoard} locale="en" />);

    expect(screen.getByRole('table', { name: 'Waiting orders and prescriptions' })).toBeTruthy();
    expect(screen.getAllByRole('row')).toHaveLength(4);
    const reference = screen.getByText('LAB-1042');
    expect(reference.tagName).toBe('CODE');
    expect(reference.getAttribute('dir')).toBe('ltr');
    expect(screen.getByText('Waiting 47 min')).toBeTruthy();
    expect(screen.getByText('Failed')).toBeTruthy();
    expect(screen.getAllByText('Unreviewed')).toHaveLength(2);
    expect(screen.getAllByText('Stuck')).toHaveLength(2);
    expect(screen.getByText('On track')).toBeTruthy();
  });

  it('renders the explicit empty state when no orders are waiting', () => {
    renderWithLocale(<PartnerBoard board={emptyBoard} locale="en" />);

    expect(screen.getByText('No orders or prescriptions waiting for this partner.')).toBeTruthy();
    expect(screen.getByText('Waiting: 0')).toBeTruthy();
    expect(screen.getByText('Stuck: 0')).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('shows Arabic labels, isolates the Arabic partner name and keeps identifiers left-to-right', () => {
    renderWithLocale(<PartnerBoard board={labBoard} locale="ar" />, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('region', { name: 'لوحة الشريك: مختبر النور' })).toBeTruthy();
    expect(screen.getByText('قيد الانتظار: 3')).toBeTruthy();
    expect(screen.getByText('متعثر: 2')).toBeTruthy();
    const partnerName = screen.getByText('مختبر النور');
    expect(partnerName.tagName).toBe('BDI');
    expect(partnerName.getAttribute('lang')).toBe('ar');
    expect(partnerName.getAttribute('dir')).toBe('auto');
    const reference = screen.getByText('LAB-1042');
    expect(reference.getAttribute('dir')).toBe('ltr');
    expect(screen.getByText('بانتظار 47 دقيقة')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'إجراء الاحتياط لـ مختبر النور' })).toBeTruthy();
  });
});

describe('PartnerBoardOverview fallback links', () => {
  it('gives each partner type its own fallback link and procedure section', () => {
    renderWithLocale(<PartnerBoardOverview boards={syntheticBoards} locale="en" />);

    expect(screen.getByText('Partner interface and outage board')).toBeTruthy();
    expect(screen.getAllByRole('region', { name: /Partner board:/ })).toHaveLength(3);

    const lab = screen.getByRole('link', { name: 'Manual fallback: Manual upload' });
    expect(lab.getAttribute('href')).toBe('#fallback-lab');
    const imaging = screen.getByRole('link', { name: 'Manual fallback: Call' });
    expect(imaging.getAttribute('href')).toBe('#fallback-imaging');
    const pharmacy = screen.getByRole('link', { name: 'Manual fallback: Print' });
    expect(pharmacy.getAttribute('href')).toBe('#fallback-pharmacy');

    expect(screen.getByRole('region', { name: 'Fallback procedure for مختبر النور' })).toBeTruthy();
    expect(
      screen.getByRole('region', { name: 'Fallback procedure for Synthetic Imaging Partner' }),
    ).toBeTruthy();
    expect(
      screen.getByRole('region', { name: 'Fallback procedure for Synthetic Pharmacy Partner' }),
    ).toBeTruthy();
    expect(document.getElementById('fallback-lab')).toBeTruthy();
    expect(document.getElementById('fallback-imaging')).toBeTruthy();
    expect(document.getElementById('fallback-pharmacy')).toBeTruthy();
    expect(
      screen.getAllByText('Manual workflows must record delivery and verification evidence.'),
    ).toHaveLength(3);
  });

  it('shows the synthetic-data notice on the overview', () => {
    renderWithLocale(
      <PartnerBoardOverview boards={[labBoard, imagingBoard, pharmacyBoard]} locale="en" />,
    );
    expect(
      screen.getByText(
        'Synthetic data only. No partner is connected; health and backlog are fixtures.',
      ),
    ).toBeTruthy();
  });
});
