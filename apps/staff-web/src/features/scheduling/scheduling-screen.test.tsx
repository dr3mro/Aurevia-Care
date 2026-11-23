import { useState } from 'react';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import type { WorklistRange } from './domain';
import { alphaOrganizationId, betaOrganizationId, syntheticAppointments } from './fixtures';
import { schedulingText } from './messages';
import { SchedulingScreen } from './SchedulingScreen';
import type { SchedulingScreenProps } from './SchedulingScreen';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Own the range and expose command callbacks so tests can observe the contract. */
function Harness({
  onCheckIn = vi.fn(),
  onBook = vi.fn(),
  onRangeChange = vi.fn(),
  organizationId = alphaOrganizationId,
}: {
  onCheckIn?: (id: string) => void;
  onBook?: SchedulingScreenProps['onBook'];
  onRangeChange?: (range: WorklistRange) => void;
  organizationId?: string;
}) {
  const [range, setRange] = useState<WorklistRange>('day');
  return (
    <SchedulingScreen
      appointments={syntheticAppointments}
      organizationId={organizationId}
      range={range}
      onRangeChange={(next) => {
        setRange(next);
        onRangeChange(next);
      }}
      onCheckIn={onCheckIn}
      onBook={onBook}
    />
  );
}

function rowFor(name: string): HTMLElement {
  const header = screen.getByRole('rowheader', { name });
  const row = header.closest('tr');
  if (!row) {
    throw new Error(`no row for ${name}`);
  }
  return row;
}

/** Fill every required booking field with a clearly synthetic value. */
function fillBooking() {
  fireEvent.change(screen.getByLabelText(schedulingText('en', 'facilityCode')), {
    target: { value: 'MAIN' },
  });
  fireEvent.change(screen.getByLabelText(schedulingText('en', 'patientId')), {
    target: { value: 'synthetic-patient-001' },
  });
  fireEvent.change(screen.getByLabelText(schedulingText('en', 'visitType')), {
    target: { value: 'follow-up' },
  });
}

describe('SchedulingScreen', () => {
  it('shows only the active tenant, ordered by scheduled time', () => {
    wrap(<Harness />);
    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
      'Synthetic Sara',
      'Synthetic Omar',
      'Synthetic Layla',
      'Synthetic Nadia',
      'Synthetic Faisal',
      'Synthetic Hana',
      'Synthetic Nora',
      'Synthetic Tom',
    ]);
    expect(screen.queryByText('Synthetic Reem')).toBeNull();
    expect(screen.queryByText('Synthetic Kareem')).toBeNull();
    expect(
      screen.getByRole('heading', { level: 2, name: schedulingText('en', 'scheduling') }),
    ).toBeTruthy();
    expect(screen.getByTestId('scheduling-status').textContent).toBe('');
  });

  it('sends check-in to the page callback and announces the request', () => {
    const onCheckIn = vi.fn();
    wrap(<Harness onCheckIn={onCheckIn} />);
    const row = rowFor('Synthetic Layla');
    fireEvent.click(within(row).getByRole('button', { name: schedulingText('en', 'checkIn') }));
    expect(onCheckIn).toHaveBeenCalledWith('apt-001');
    expect(screen.getByTestId('scheduling-status').textContent).toContain('Synthetic Layla');
    expect(screen.getByTestId('scheduling-status').textContent).toContain(
      schedulingText('en', 'commandSent'),
    );
  });

  it('assigns an appointment locally and announces the assignee', () => {
    wrap(<Harness />);
    const row = rowFor('Synthetic Omar');
    fireEvent.change(within(row).getByLabelText(schedulingText('en', 'assign')), {
      target: { value: 'synthetic.nurse' },
    });
    expect(within(row).getByText('synthetic.nurse')).toBeTruthy();
    expect(screen.getByTestId('scheduling-status').textContent).toContain('synthetic.nurse');
  });

  it('reports a day/week change to the page that owns the API query', () => {
    const onRangeChange = vi.fn();
    wrap(<Harness onRangeChange={onRangeChange} />);
    const week = screen.getByRole('button', { name: schedulingText('en', 'week') });
    expect(week.getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(week);
    expect(onRangeChange).toHaveBeenCalledWith('week');
    expect(week.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'day') }));
    expect(onRangeChange).toHaveBeenCalledWith('day');
  });

  it('sends a complete booking draft to the page and then detects its protected-slot conflict', () => {
    const onBook = vi.fn();
    wrap(<Harness onBook={onBook} />);
    fillBooking();
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'resource')), {
      target: { value: 'room-9' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'start')), {
      target: { value: '2026-03-09T07:00:00Z' },
    });
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'book') }));
    expect(onBook).toHaveBeenCalledWith({
      organizationId: alphaOrganizationId,
      resourceId: 'room-9',
      scheduledAt: '2026-03-09T07:00:00Z',
      facilityCode: 'MAIN',
      patientId: 'synthetic-patient-001',
      visitType: 'follow-up',
    });

    const status = screen.getByTestId('conflict-status');
    expect(status.textContent).toBe('');
    // The local warning is advisory: it uses a fixture slot already occupied
    // in this tenant, while the server owns real slot exclusivity.
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'resource')), {
      target: { value: 'room-1' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'start')), {
      target: { value: '2026-03-09T10:30:00Z' },
    });
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'book') }));
    expect(status.textContent).toBe(schedulingText('en', 'conflictHint'));
    expect(onBook).toHaveBeenCalledTimes(1);
  });

  it('never treats the other tenant with the same local id as a conflict', () => {
    wrap(<Harness organizationId={betaOrganizationId} />);
    fillBooking();
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'resource')), {
      target: { value: 'clinician-7' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'start')), {
      target: { value: '2026-03-09T09:00:00Z' },
    });
    expect(screen.getByTestId('conflict-status').textContent).toBe('');
    expect(screen.getAllByRole('rowheader').map((cell) => cell.textContent)).toEqual([
      'Synthetic Reem',
      'Synthetic Kareem',
      'Synthetic Zain',
    ]);
    expect(screen.queryByText('Synthetic Layla')).toBeNull();
  });

  it('renders bilingual RTL copy under the Arabic locale', () => {
    wrap(<Harness />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(
      screen.getByRole('heading', { level: 2, name: schedulingText('ar', 'scheduling') }),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: schedulingText('ar', 'week') })).toBeTruthy();
  });
});
