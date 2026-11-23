import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { BREAKPOINT_MEDIA_QUERIES } from '../../theme/breakpoints';
import type { Locale } from '../../i18n/messages';
import { BookingCheckPanel } from './BookingCheckPanel';
import { CheckInPanel } from './CheckInPanel';
import { NoShowReasonForm } from './NoShowReasonForm';
import { ReceptionHeader } from './ReceptionHeader';
import { ReceptionNav } from './ReceptionNav';
import { StateSummaryCards } from './StateSummaryCards';
import { VisitStateBadge } from './VisitStateBadge';
import type { FreeSlot, VisitState } from './domain';
import {
  alphaOrganizationId,
  betaOrganizationId,
  receptionChecker,
  receptionFacilities,
  receptionIdleMinutes,
  receptionIdentityFor,
  receptionStaleSlot,
  receptionUnreadMessages,
  syntheticAlphaAppointments,
  syntheticBetaAppointments,
} from './fixtures';
import { schedulingText, schedulingTextWithValue, visitStateMessageKey } from './messages';

afterEach(() => {
  vi.unstubAllGlobals();
});

/** Force the compact layout band by making both min-width queries fail. */
function forceCompact() {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
  expect(BREAKPOINT_MEDIA_QUERIES.medium).toContain('768px');
}

function wrap(ui: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

function required<T>(value: T | undefined): T {
  if (value === undefined) {
    throw new Error('Expected a synthetic fixture value');
  }
  return value;
}

/** Narrow a DOM lookup that Testing Library reports as nullable. */
function requiredElement(value: Element | null): Element {
  if (value === null) {
    throw new Error('Expected a rendered element');
  }
  return value;
}

const booked = required(syntheticAlphaAppointments.find((item) => item.id === 'apt-001'));
const checkedIn = required(syntheticAlphaAppointments.find((item) => item.id === 'apt-002'));

describe('VisitStateBadge tints', () => {
  const tones: Record<VisitState, string> = {
    booked: 'info',
    'checked-in': 'success',
    'in-progress': 'warning',
    completed: 'neutral',
    cancelled: 'neutral',
    'no-show': 'critical',
  };

  it('shows the word, an aria-hidden dot and a token tint for every state', () => {
    for (const state of Object.keys(tones) as VisitState[]) {
      const { unmount } = wrap(<VisitStateBadge state={state} locale="en" />);
      const label = schedulingText('en', visitStateMessageKey[state]);
      const badge = requiredElement(screen.getByText(label).closest('[data-state]'));
      expect(badge.getAttribute('data-state')).toBe(state);
      expect(badge.getAttribute('data-tone')).toBe(tones[state]);
      expect(badge.parentElement?.querySelector('[data-badge-dot]')?.getAttribute('aria-hidden')).toBe('true');
      expect(badge.querySelector('[data-badge-icon]')?.getAttribute('aria-hidden')).toBe('true');
      unmount();
    }
  });
});

describe('ReceptionNav', () => {
  it('marks Today active and shows the unread badge and session footer', () => {
    wrap(
      <ReceptionNav
        unreadMessages={3}
        roleName={schedulingText('en', 'roleReception')}
        userName="Synthetic Receptionist"
        idleMinutes={14}
      />,
    );
    expect(screen.getByRole('navigation', { name: schedulingText('en', 'navLabel') })).toBeTruthy();
    expect(screen.getByRole('button', { name: schedulingText('en', 'navToday') }).getAttribute('aria-current')).toBe('page');
    expect(screen.getByText(schedulingTextWithValue('en', 'navUnread', 3))).toBeTruthy();
    expect(screen.getByText('Synthetic Receptionist')).toBeTruthy();
    expect(screen.getByText(schedulingTextWithValue('en', 'navIdle', 14))).toBeTruthy();
    expect(screen.getByRole('navigation').getAttribute('data-layout')).toBe('side-column');
  });

  it('hides a zero unread badge', () => {
    wrap(
      <ReceptionNav
        unreadMessages={0}
        roleName={schedulingText('en', 'roleReception')}
        userName="Synthetic Receptionist"
        idleMinutes={14}
      />,
    );
    expect(screen.queryByText(schedulingTextWithValue('en', 'navUnread', 0))).toBeNull();
  });
});

describe('ReceptionHeader', () => {
  it('shows the date, facility and zone and wires facility and language controls', () => {
    const onFacilityChange = vi.fn();
    const onNewBooking = vi.fn();
    wrap(
      <ReceptionHeader
        facilities={receptionFacilities}
        facilityCode={receptionFacilities[0]?.code ?? ''}
        onFacilityChange={onFacilityChange}
        onNewBooking={onNewBooking}
      />,
    );
    expect(screen.getByRole('heading', { level: 1, name: schedulingText('en', 'today') })).toBeTruthy();
    expect(screen.getAllByText(receptionFacilities[0]?.name ?? '').length).toBeGreaterThan(0);
    expect(screen.getByText(receptionFacilities[0]?.timeZone ?? '')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'facilitySelect')), {
      target: { value: receptionFacilities[1]?.code ?? '' },
    });
    expect(onFacilityChange).toHaveBeenCalledWith(receptionFacilities[1]?.code);
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'newBooking') }));
    expect(onNewBooking).toHaveBeenCalledTimes(1);
  });

  it('switches to Arabic and flips the layout direction', () => {
    wrap(
      <ReceptionHeader
        facilities={receptionFacilities}
        facilityCode={receptionFacilities[0]?.code ?? ''}
        onFacilityChange={vi.fn()}
        onNewBooking={vi.fn()}
      />,
      'en',
    );
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'arabic') }));
    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('button', { name: schedulingText('ar', 'english') })).toBeTruthy();
  });
});

describe('StateSummaryCards', () => {
  it('renders five state cards with counts and notes', () => {
    wrap(<StateSummaryCards appointments={syntheticAlphaAppointments} />);
    expect(screen.getAllByTestId('state-card')).toHaveLength(5);
    expect(screen.getByText(schedulingText('en', 'cardBooked'))).toBeTruthy();
    expect(screen.getByText(schedulingTextWithValue('en', 'noteNeedCheckIn', 3))).toBeTruthy();
    expect(screen.getByTestId('state-card-booked').textContent).toContain('3');
  });
});

describe('CheckInPanel', () => {
  it('shows the identity context and records the checker, time and facility', () => {
    const onCheckIn = vi.fn();
    wrap(
      <CheckInPanel
        appointment={booked}
        identity={receptionIdentityFor(booked, alphaOrganizationId)}
        checker={receptionChecker}
        onCheckIn={onCheckIn}
      />,
    );
    expect(screen.getByText(schedulingText('en', 'dateOfBirth'))).toBeTruthy();
    expect(screen.getByText(schedulingText('en', 'selfPayBalance'))).toBeTruthy();
    expect(screen.getByText(schedulingText('en', 'verified'))).toBeTruthy();
    const checkboxes = screen.getAllByRole<HTMLInputElement>('checkbox');
    expect(checkboxes).toHaveLength(3);
    fireEvent.click(required(checkboxes[2]));
    expect(required(checkboxes[2]).checked).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'checkInNotify') }));
    expect(onCheckIn).toHaveBeenCalledTimes(1);
    expect(onCheckIn).toHaveBeenCalledWith({
      checker: receptionChecker,
      at: '2026-03-09T09:30:00Z',
      facilityCode: booked.facilityCode,
    });
  });

  it('asks for a row when no appointment is selected', () => {
    wrap(
      <CheckInPanel appointment={undefined} identity={undefined} checker={receptionChecker} onCheckIn={vi.fn()} />,
    );
    expect(screen.getByText(schedulingText('en', 'selectRow'))).toBeTruthy();
  });
});

describe('BookingCheckPanel', () => {
  it('warns on a stale slot and books one free slot exactly once', () => {
    const onPickSlot = vi.fn();
    wrap(<BookingCheckPanel staleSlot={receptionStaleSlot} onPickSlot={onPickSlot} />);
    expect(screen.getByRole('alert').textContent).toContain(receptionStaleSlot.label);
    const slots = screen.getAllByTestId('free-slot');
    expect(slots).toHaveLength(3);
    const first = required(slots[0]);
    const second = required(slots[1]);
    fireEvent.click(within(first).getByRole('button'));
    fireEvent.click(within(second).getByRole('button'));
    expect(onPickSlot).toHaveBeenCalledTimes(1);
    expect(onPickSlot).toHaveBeenCalledWith(receptionStaleSlot.freeSlots[0]);
  });

  it('is quiet when no slot was just taken', () => {
    wrap(<BookingCheckPanel staleSlot={undefined} onPickSlot={vi.fn()} />);
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryAllByTestId('free-slot')).toHaveLength(0);
  });
});

describe('NoShowReasonForm', () => {
  it('blocks an empty reason and records a reason with actor and time', () => {
    const onRecord = vi.fn();
    wrap(
      <NoShowReasonForm
        patientName={booked.patientName.latinName}
        actor={receptionChecker}
        onRecord={onRecord}
      />,
    );
    const submit = screen.getByRole('button', { name: schedulingText('en', 'recordReason') });
    fireEvent.click(submit);
    expect(screen.getByRole('alert').textContent).toBe(schedulingText('en', 'reasonRequired'));
    expect(onRecord).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText(schedulingText('en', 'reasonLabel')), {
      target: { value: '  unreachable  ' },
    });
    fireEvent.click(submit);
    expect(onRecord).toHaveBeenCalledTimes(1);
    expect(onRecord).toHaveBeenCalledWith({
      reason: 'unreachable',
      actor: receptionChecker,
      at: '2026-03-09T09:30:00Z',
    });
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('Reception fixtures', () => {
  it('keeps the checked-in synthetic row available for handoff coverage', () => {
    expect(checkedIn.state).toBe('checked-in');
    expect(checkedIn.assignee).toBeTruthy();
    const free: readonly FreeSlot[] = receptionStaleSlot.freeSlots;
    expect(free.length).toBe(3);
  });

  it('keys identity context by tenant so colliding local ids do not leak', () => {
    const betaFirst = required(syntheticBetaAppointments.find((item) => item.id === 'apt-001'));
    const alphaIdentity = receptionIdentityFor(booked, alphaOrganizationId);
    const betaIdentity = receptionIdentityFor(betaFirst, betaOrganizationId);
    const wrongTenant = receptionIdentityFor(booked, betaOrganizationId);
    expect(alphaIdentity.mrn).toBe('100233');
    expect(betaIdentity.mrn).toBe('200411');
    expect(wrongTenant.mrn).not.toBe(alphaIdentity.mrn);
    expect(receptionIdentityFor(checkedIn, alphaOrganizationId).mrn).toBe('100877');
  });
});

describe('ReceptionNav responsive layout', () => {
  it('moves the rail to a top bar on compact viewports', () => {
    forceCompact();
    wrap(
      <ReceptionNav
        unreadMessages={receptionUnreadMessages}
        roleName="Reception"
        userName="Synthetic Receptionist"
        idleMinutes={receptionIdleMinutes}
      />,
    );
    expect(screen.getByRole('navigation').getAttribute('data-layout')).toBe('top-bar');
    expect(screen.getByRole('navigation').getAttribute('data-breakpoint')).toBe('compact');
  });
});
