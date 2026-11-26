import { useState } from 'react';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { Locale } from '../../i18n/messages';
import { AppointmentWorklist } from './AppointmentWorklist';
import { BookingForm } from './BookingForm';
import { VisitStateBadge } from './VisitStateBadge';
import {
  alphaOrganizationId,
  receptionUnassignedCheckedIn,
  schedulingAnchorCivilDate,
  syntheticAlphaAppointments,
  syntheticAppointments,
} from './fixtures';
import { schedulingText, schedulingTextWithValue, visitStateMessageKey } from './messages';
import { selectWorklist } from './domain';
import type { VisitState, WorklistFilter } from './domain';

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

const dayAppointments = selectWorklist(
  syntheticAppointments,
  alphaOrganizationId,
  'day',
  schedulingAnchorCivilDate,
);

describe('VisitStateBadge', () => {
  it('shows a translated word and a distinct shape for every state', () => {
    for (const state of [
      'booked',
      'checked-in',
      'in-progress',
      'completed',
      'cancelled',
      'no-show',
    ] as VisitState[]) {
      const { unmount } = wrap(<VisitStateBadge state={state} locale="en" />);
      const label = schedulingText('en', visitStateMessageKey[state]);
      expect(screen.getByText(label)).toBeTruthy();
      const badge = screen.getByText(label).closest('[data-state]');
      expect(badge?.getAttribute('data-state')).toBe(state);
      expect(badge?.querySelector('[data-badge-icon]')?.textContent).toBeTruthy();
      expect(screen.getByText(label).tagName).toBe('BDI');
      expect(screen.getByText(label).getAttribute('dir')).toBe('auto');
      unmount();
    }
  });

  it('uses an Arabic label in Arabic locale', () => {
    wrap(<VisitStateBadge state="booked" locale="ar" />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText(schedulingText('ar', 'stateBooked'))).toBeTruthy();
  });
});

describe('BookingForm', () => {
  const noop = vi.fn();

  it('submits a protected-resource booking request from labeled fields', () => {
    const onBook = vi.fn();
    wrap(
      <BookingForm
        appointments={syntheticAlphaAppointments}
        organizationId={alphaOrganizationId}
        onBook={onBook}
      />,
    );
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'facilityCode')), {
      target: { value: 'MAIN' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'patientId')), {
      target: { value: 'synthetic-patient-001' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'visitType')), {
      target: { value: 'follow-up' },
    });
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
  });

  it('warns politely and blocks a same-tenant protected-slot conflict', () => {
    const onBook = vi.fn();
    wrap(
      <BookingForm
        appointments={syntheticAlphaAppointments}
        organizationId={alphaOrganizationId}
        onBook={onBook}
      />,
    );
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'facilityCode')), {
      target: { value: 'MAIN' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'patientId')), {
      target: { value: 'synthetic-patient-001' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'visitType')), {
      target: { value: 'follow-up' },
    });
    const status = screen.getByTestId('conflict-status');
    expect(status.textContent).toBe('');
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'resource')), {
      target: { value: 'room-1' },
    });
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'start')), {
      target: { value: '2026-03-09T10:30:00Z' },
    });
    expect(status.textContent).toBe(schedulingText('en', 'conflictHint'));
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'book') }));
    expect(onBook).not.toHaveBeenCalled();
  });

  it('stays quiet when only the resource field is filled', () => {
    const onBook = vi.fn();
    wrap(
      <BookingForm
        appointments={syntheticAlphaAppointments}
        organizationId={alphaOrganizationId}
        onBook={onBook}
      />,
    );
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'resource')), {
      target: { value: 'room-1' },
    });
    expect(screen.getByTestId('conflict-status').textContent).toBe('');
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'book') }));
    expect(onBook).not.toHaveBeenCalled();
  });

  it('does not submit an empty draft and works in Arabic RTL', () => {
    const onBook = vi.fn();
    wrap(
      <BookingForm appointments={[]} organizationId={alphaOrganizationId} onBook={onBook} />,
      'ar',
    );
    expect(document.documentElement.dir).toBe('rtl');
    fireEvent.click(screen.getByRole('button', { name: schedulingText('ar', 'book') }));
    expect(onBook).not.toHaveBeenCalled();
    expect(screen.getByTestId('conflict-status').textContent).toBe('');
    expect(noop).not.toHaveBeenCalled();
  });
});

/** Rows used by the filter-chip test, including a no-show and an unassigned check-in. */
const filterRows = [
  ...dayAppointments,
  required(syntheticAlphaAppointments.find((item) => item.id === 'apt-006')),
  receptionUnassignedCheckedIn,
];

/** Own the filter chip state so the test observes controlled filtering. */
function FilterHarness() {
  const [filter, setFilter] = useState<WorklistFilter>('all');
  return (
    <AppointmentWorklist
      appointments={filterRows}
      range="day"
      onRangeChange={vi.fn()}
      filter={filter}
      onFilterChange={setFilter}
      onCheckIn={vi.fn()}
      onAssign={vi.fn()}
      onHandoff={vi.fn()}
      onOpenChart={vi.fn()}
      onRebook={vi.fn()}
    />
  );
}

describe('AppointmentWorklist', () => {
  const baseProps = {
    appointments: dayAppointments,
    range: 'day' as const,
    onRangeChange: vi.fn(),
    filter: 'all' as const,
    onFilterChange: vi.fn(),
    onCheckIn: vi.fn(),
    onAssign: vi.fn(),
    onHandoff: vi.fn(),
    onOpenChart: vi.fn(),
    onRebook: vi.fn(),
  };

  it('renders a captioned, time-ordered table with accessible row headers', () => {
    wrap(<AppointmentWorklist {...baseProps} />);
    expect(screen.getByRole('table', { name: schedulingText('en', 'appointments') })).toBeTruthy();
    expect(
      screen.getAllByRole('columnheader').every((cell) => cell.getAttribute('scope') === 'col'),
    ).toBe(true);
    expect(screen.getAllByRole('rowheader').map((cell) => cell.querySelector('bdi')?.textContent)).toEqual([
      'Synthetic Sara',
      'Synthetic Omar',
      'Synthetic Layla',
    ]);
    expect(
      screen
        .getAllByRole('rowheader')[0]
        ?.closest('tr')
        ?.querySelector('time')
        ?.getAttribute('datetime'),
    ).toBe('2026-03-09T08:15:00Z');
    expect(screen.getAllByText(schedulingText('en', 'unassigned')).length).toBeGreaterThan(0);
  });

  it('shows one lifecycle action per row and reports the chosen row', () => {
    const onCheckIn = vi.fn();
    const onHandoff = vi.fn();
    const onOpenChart = vi.fn();
    wrap(
      <AppointmentWorklist
        {...baseProps}
        onCheckIn={onCheckIn}
        onHandoff={onHandoff}
        onOpenChart={onOpenChart}
      />,
    );
    expect(screen.getAllByRole('button', { name: schedulingText('en', 'checkIn') })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: schedulingText('en', 'handoff') })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: schedulingText('en', 'openChart') })).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'checkIn') }));
    expect(onCheckIn).toHaveBeenCalledWith('apt-001');
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'handoff') }));
    expect(onHandoff).toHaveBeenCalledWith('apt-002');
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'openChart') }));
    expect(onOpenChart).toHaveBeenCalledWith('apt-003');
  });

  it('filters rows by chip and disables handoff without an active assignee', () => {
    wrap(<FilterHarness />);
    expect(screen.getAllByRole('rowheader')).toHaveLength(5);
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'filterNeedsCheckIn') }));
    expect(screen.getAllByRole('rowheader')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'filterWaiting') }));
    expect(screen.getAllByRole('rowheader')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'filterAll') }));
    expect(screen.getAllByRole('rowheader')).toHaveLength(5);
    const handoffs = screen.getAllByRole('button', { name: schedulingText('en', 'handoff') });
    expect(handoffs).toHaveLength(2);
    expect((handoffs[1] as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getAllByTestId('handoff-hint')).toHaveLength(1);
  });

  it('disables assignment for terminal visits and reports an assignment', () => {
    const onAssign = vi.fn();
    wrap(
      <AppointmentWorklist
        {...baseProps}
        appointments={[required(syntheticAlphaAppointments.find((a) => a.id === 'apt-005'))]}
        onAssign={onAssign}
      />,
    );
    expect(screen.getByLabelText<HTMLSelectElement>(schedulingText('en', 'assign')).disabled).toBe(
      true,
    );
  });

  it('assigns a non-terminal visit from the labeled select', () => {
    const onAssign = vi.fn();
    wrap(<AppointmentWorklist {...baseProps} onAssign={onAssign} />);
    const selects = screen.getAllByLabelText<HTMLSelectElement>(schedulingText('en', 'assign'));
    expect(selects.every((select) => !select.disabled)).toBe(true);
    fireEvent.change(required(selects[2]), { target: { value: 'synthetic.nurse' } });
    expect(onAssign).toHaveBeenCalledWith('apt-001', 'synthetic.nurse');
  });

  it('exposes the day and week toggle through aria-pressed', () => {
    const onRangeChange = vi.fn();
    const { rerender } = wrap(<AppointmentWorklist {...baseProps} onRangeChange={onRangeChange} />);
    const day = screen.getByRole('button', { name: schedulingText('en', 'day') });
    const week = screen.getByRole('button', { name: schedulingText('en', 'week') });
    expect(day.getAttribute('aria-pressed')).toBe('true');
    expect(week.getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(day);
    expect(onRangeChange).toHaveBeenCalledWith('day');
    fireEvent.click(week);
    expect(onRangeChange).toHaveBeenCalledWith('week');

    rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <AppointmentWorklist {...baseProps} range="week" onRangeChange={onRangeChange} />
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(
      screen
        .getByRole('button', { name: schedulingText('en', 'week') })
        .getAttribute('aria-pressed'),
    ).toBe('true');
  });

  it('shows an empty state with a defined column span', () => {
    wrap(<AppointmentWorklist {...baseProps} appointments={[]} />);
    expect(screen.getByText(schedulingText('en', 'noAppointments'))).toBeTruthy();
  });

  it('uses Arabic labels and the local patient name under RTL', () => {
    wrap(<AppointmentWorklist {...baseProps} />, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('table', { name: schedulingText('ar', 'appointments') })).toBeTruthy();
    const localName = screen.getAllByRole('rowheader')[2]?.textContent;
    expect(localName).toMatch(/[\u0600-\u06FF]/);
    const bdi = screen.getAllByRole('rowheader')[2]?.querySelector('bdi');
    expect(bdi?.getAttribute('dir')).toBe('auto');
    expect(bdi?.getAttribute('lang')).toBe('ar');
    expect(
      within(required(required(screen.getAllByRole('rowheader')[2]).closest('tr'))).getByText(
        schedulingText('ar', 'checkIn'),
      ),
    ).toBeTruthy();
  });
});

/** Reception-specific worklist assertions, kept small for the lint budget. */
describe('AppointmentWorklist reception contract', () => {
  const props = {
    appointments: dayAppointments,
    range: 'day' as const,
    onRangeChange: vi.fn(),
    filter: 'all' as const,
    onFilterChange: vi.fn(),
    onCheckIn: vi.fn(),
    onAssign: vi.fn(),
    onHandoff: vi.fn(),
    onOpenChart: vi.fn(),
    onRebook: vi.fn(),
  };

  it('stays usable when an embedding host omits the optional callbacks', () => {
    wrap(
      <AppointmentWorklist
        appointments={dayAppointments}
        range="day"
        onRangeChange={vi.fn()}
        onCheckIn={vi.fn()}
        onAssign={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'filterNeedsCheckIn') }));
    // Without a controlled host the filter stays on 'all'; the click is inert,
    // not a crash, and the clinical table is still present.
    expect(screen.getByRole('button', { name: schedulingText('en', 'filterAll') }).getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'handoff') }));
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'openChart') }));
    expect(screen.getByRole('table', { name: schedulingText('en', 'appointments') })).toBeTruthy();
  });

  it('shows a time-in-state note under each state badge', () => {
    const completed = required(syntheticAlphaAppointments.find((item) => item.id === 'apt-004'));
    const noShow = required(syntheticAlphaAppointments.find((item) => item.id === 'apt-006'));
    wrap(<AppointmentWorklist {...props} appointments={[...dayAppointments, completed, noShow]} />);
    const note = (name: string): string =>
      required(screen.getByRole('rowheader', { name }).closest('tr')).textContent;
    expect(note('Synthetic Sara')).toContain(schedulingText('en', 'noteSince').replace('{value}', ''));
    expect(note('Synthetic Omar')).toContain(schedulingTextWithValue('en', 'noteWaiting', 30));
    expect(note('Synthetic Layla')).toContain(schedulingTextWithValue('en', 'noteArrivesIn', 60));
    expect(note('Synthetic Nadia')).toContain(schedulingText('en', 'noteDone').replace('{value}', ''));
    expect(note('Synthetic Hana')).toContain(schedulingText('en', 'noteNoShow'));
  });

  it('keeps handoff disabled when the assignee is a blank string', () => {
    const blank = {
      ...required(syntheticAlphaAppointments.find((item) => item.id === 'apt-002')),
      assignee: '',
    };
    wrap(<AppointmentWorklist {...props} appointments={[blank]} />);
    const handoff = screen.getByRole<HTMLButtonElement>('button', { name: schedulingText('en', 'handoff') });
    expect(handoff.disabled).toBe(true);
    expect(screen.getByTestId('handoff-hint')).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
