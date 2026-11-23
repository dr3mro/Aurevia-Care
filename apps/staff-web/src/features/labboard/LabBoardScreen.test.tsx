import { render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';

import type { Locale } from '../../i18n/messages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { LabBoardOverview, TurnaroundCard } from './LabBoardScreen';
import { emptyBoard, healthyBoard, outageBoard } from './fixtures';
import type { TurnaroundOrder } from './domain';

/** Every screen needs the theme tokens and a locale-owning provider. */
function renderWithLocale(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

describe('lab board columns', () => {
  it('renders exactly five columns in workflow order and counts the cards in each', () => {
    renderWithLocale(<LabBoardOverview board={healthyBoard} locale="en" />);

    const columns = screen.getAllByTestId('turnaround-column');
    expect(columns).toHaveLength(5);
    const expected = [
      { stage: 'ordered', label: 'Ordered', count: 2 },
      { stage: 'collected', label: 'Collected', count: 2 },
      { stage: 'in-analysis', label: 'In analysis', count: 1 },
      { stage: 'awaiting-verification', label: 'Awaiting verification', count: 1 },
      { stage: 'released', label: 'Released', count: 1 },
    ];
    expected.forEach(({ stage, label, count }, index) => {
      const column = required(columns[index]);
      expect(column.getAttribute('data-stage')).toBe(stage);
      expect(within(column).getByRole('heading', { level: 3 }).textContent).toContain(label);
      expect(within(column).getAllByTestId('turnaround-card')).toHaveLength(count);
    });
  });

  it('renders an explicit empty state in every column when the board has no orders', () => {
    renderWithLocale(<LabBoardOverview board={emptyBoard} locale="en" />);

    expect(screen.getAllByText('No orders in this column.')).toHaveLength(5);
    expect(screen.queryAllByTestId('turnaround-card')).toHaveLength(0);
  });
});

describe('lab board overdue wording and colour', () => {
  it('words overdue cards with the overrun and colours only those cards', () => {
    renderWithLocale(<LabBoardOverview board={healthyBoard} locale="en" />);

    expect(screen.getByText('Overdue by 25 min')).toBeTruthy();
    expect(screen.getByText('Overdue by 45 min')).toBeTruthy();
    expect(screen.getByText('Overdue by 10 min')).toBeTruthy();
    expect(screen.getAllByText('On track')).toHaveLength(4);

    const overdueCards = screen
      .getAllByTestId('turnaround-card')
      .filter((card) => card.getAttribute('data-overdue') === 'true');
    expect(overdueCards).toHaveLength(3);
    // The word carries the meaning; colour is only an additional cue and is
    // applied to overdue cards, never to on-track cards.
    expect(screen.getByText('Overdue by 25 min').style.color).toBe('rgb(179, 38, 30)');
    expect(required(screen.getAllByText('On track')[0]).style.color).toBe('');
  });

  it('shows age against the target on each card with an isolated accession', () => {
    const order: TurnaroundOrder = {
      id: 'synthetic-order',
      accession: 'SYN-LB-1002',
      stage: 'ordered',
      ageMinutes: 55,
      targetMinutes: 30,
    };
    renderWithLocale(<TurnaroundCard order={order} locale="en" />);

    expect(screen.getByText('Age 55 min · target 30 min')).toBeTruthy();
    const accession = screen.getByText('SYN-LB-1002');
    expect(accession.tagName).toBe('CODE');
    expect(accession.getAttribute('dir')).toBe('ltr');
  });
});

describe('lab board partner outage banner', () => {
  it('shows a persistent alert with the manual route link when the interface is down', () => {
    renderWithLocale(<LabBoardOverview board={outageBoard} locale="en" />);

    expect(screen.getByRole('alert', { name: 'Partner interface outage' })).toBeTruthy();
    expect(screen.getByText('Partner interface down')).toBeTruthy();
    const link = screen.getByRole('link', { name: 'Open the manual route' });
    expect(link.getAttribute('href')).toBe('#lab-manual-route');
    expect(screen.getByText('Manual route for laboratory orders')).toBeTruthy();
  });

  it('hides the outage banner when the interface is healthy', () => {
    renderWithLocale(<LabBoardOverview board={healthyBoard} locale="en" />);

    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('link', { name: 'Open the manual route' })).toBeNull();
  });
});

describe('lab board English and Arabic rendering', () => {
  it('renders the English board with left-to-right document direction', () => {
    renderWithLocale(<LabBoardOverview board={outageBoard} locale="en" />, 'en');

    expect(document.documentElement.dir).toBe('ltr');
    expect(screen.getByText('Laboratory turnaround board')).toBeTruthy();
    expect(screen.getAllByTestId('turnaround-column')).toHaveLength(5);
  });

  it('mirrors Arabic, translates the board and keeps numbers in Latin digits', () => {
    renderWithLocale(<LabBoardOverview board={outageBoard} locale="ar" />, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText('لوحة زمن إنجاز التحاليل')).toBeTruthy();
    expect(screen.getByRole('alert', { name: 'انقطاع واجهة الشريك' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'فتح المسار اليدوي' })).toBeTruthy();
    expect(screen.getByText('متأخرة بـ 25 دقيقة')).toBeTruthy();
    const accession = screen.getByText('SYN-LB-1002');
    expect(accession.getAttribute('dir')).toBe('ltr');
    expect(screen.getAllByTestId('turnaround-column')).toHaveLength(5);
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
