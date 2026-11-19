import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import { alphaOrganizationId, betaOrganizationId, syntheticAppointments } from './fixtures';
import { schedulingText } from './messages';
import { SchedulingScreen } from './SchedulingScreen';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function alphaScreen() {
  return wrap(<SchedulingScreen appointments={syntheticAppointments} organizationId={alphaOrganizationId} />);
}

function rowFor(name: string): HTMLElement {
  const header = screen.getByRole('rowheader', { name });
  const row = header.closest('tr');
  if (!row) {
    throw new Error(`no row for ${name}`);
  }
  return row;
}

describe('SchedulingScreen', () => {
  it('shows only the active tenant, ordered by scheduled time', () => {
    alphaScreen();
    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
      'Synthetic Sara',
      'Synthetic Omar',
      'Synthetic Layla',
    ]);
    expect(screen.queryByText('Synthetic Reem')).toBeNull();
    expect(screen.queryByText('Synthetic Kareem')).toBeNull();
    expect(screen.getByRole('heading', { level: 2, name: schedulingText('en', 'scheduling') })).toBeTruthy();
    expect(screen.getByTestId('scheduling-status').textContent).toBe('');
  });

  it('checks in one appointment and announces the change', () => {
    alphaScreen();
    const row = rowFor('Synthetic Layla');
    fireEvent.click(within(row).getByRole('button', { name: schedulingText('en', 'checkIn') }));
    expect(within(row).getByText(schedulingText('en', 'stateCheckedIn'))).toBeTruthy();
    expect(screen.getByTestId('scheduling-status').textContent).toContain('Synthetic Layla');
    expect((within(row).getByRole('button', { name: schedulingText('en', 'checkIn') }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('assigns an appointment and announces the assignee', () => {
    alphaScreen();
    const row = rowFor('Synthetic Omar');
    fireEvent.change(within(row).getByLabelText(schedulingText('en', 'assign')), {
      target: { value: 'synthetic.nurse' },
    });
    expect(within(row).getByText('synthetic.nurse')).toBeTruthy();
    expect(screen.getByTestId('scheduling-status').textContent).toContain('synthetic.nurse');
  });

  it('switches from the day to the week worklist', () => {
    alphaScreen();
    expect(screen.queryByText('Synthetic Nora')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'week') }));
    expect(screen.getByText('Synthetic Nora')).toBeTruthy();
    expect(screen.queryByText('Synthetic Tom')).toBeNull();
  });

  it('books a synthetic visit and then detects its protected-slot conflict', () => {
    alphaScreen();
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'resource')), {
      target: { value: 'room-9' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'start')), {
      target: { value: '2026-03-09T07:00:00Z' },
    });
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'book') }));
    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toContain('Synthetic Walk-in');

    const status = screen.getByTestId('conflict-status');
    expect(status.textContent).toBe('');
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'resource')), {
      target: { value: 'room-9' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'start')), {
      target: { value: '2026-03-09T07:00:00Z' },
    });
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'book') }));
    expect(status.textContent).toBe(schedulingText('en', 'conflictHint'));
    expect(screen.getAllByRole('rowheader').filter((cell) => cell.textContent === 'Synthetic Walk-in')).toHaveLength(1);
  });

  it('never treats the other tenant with the same local id as a conflict', () => {
    const { rerender } = alphaScreen();
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'resource')), {
      target: { value: 'clinician-7' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'start')), {
      target: { value: '2026-03-09T09:00:00Z' },
    });
    expect(screen.getByTestId('conflict-status').textContent).toBe(schedulingText('en', 'conflictHint'));

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <SchedulingScreen appointments={syntheticAppointments} organizationId={betaOrganizationId} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual(['Synthetic Reem']);
    expect(screen.queryByText('Synthetic Layla')).toBeNull();
    expect(screen.getByTestId('conflict-status').textContent).toBe('');
  });

  it('renders bilingual RTL copy under the Arabic locale', () => {
    wrap(
      <SchedulingScreen appointments={syntheticAppointments} organizationId={alphaOrganizationId} />,
      'ar',
    );
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { level: 2, name: schedulingText('ar', 'scheduling') })).toBeTruthy();
    expect(screen.getByRole('button', { name: schedulingText('ar', 'week') })).toBeTruthy();
  });
});
