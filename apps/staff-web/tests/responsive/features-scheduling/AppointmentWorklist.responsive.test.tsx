import { act, fireEvent, render, screen, within } from '@testing-library/react';
import type { RenderResult } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppointmentWorklist } from '../../../src/features/scheduling/AppointmentWorklist';
import { selectWorklist } from '../../../src/features/scheduling/domain';
import type { Appointment } from '../../../src/features/scheduling/domain';
import {
  alphaOrganizationId,
  schedulingAnchorCivilDate,
  syntheticAlphaAppointments,
} from '../../../src/features/scheduling/fixtures';
import { schedulingText } from '../../../src/features/scheduling/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES, breakpointFor } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type Band = 'compact' | 'medium' | 'expanded';
type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query. One state object per
 * query is shared by the hook's initializer and its effect so a fired change
 * reaches the same listener. No pixels are laid out by jsdom: the assertions
 * below inspect classes, attributes, roles and structure only (WEB-07, UX-01).
 */
function installMatchMedia(initial: Record<string, boolean>) {
  const states = new Map<string, QueryState>();
  const state = (query: string): QueryState => {
    let current = states.get(query);
    if (!current) {
      current = { matches: initial[query] ?? false, listeners: new Set() };
      states.set(query, current);
    }
    return current;
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const current = state(query);
      return {
        get matches() {
          return current.matches;
        },
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      };
    }),
  );
  return {
    fire(query: string, matches: boolean) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

/** Answers for the three layout bands; compact is the absence of both queries. */
function answers(band: Band): Record<string, boolean> {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
  };
}

const onRangeChange = vi.fn();
const onCheckIn = vi.fn();
const onAssign = vi.fn();

const dayAppointments = selectWorklist(
  syntheticAlphaAppointments,
  alphaOrganizationId,
  'day',
  schedulingAnchorCivilDate,
);

const LONG_ENGLISH =
  'A deliberately long synthetic patient name that has to wrap on a narrow phone without clipping or overlap';
const LONG_ARABIC = 'اسم مريض تجريبي طويل جداً يجب أن يلتف في شاشة الهاتف دون قص أو تداخل';

/** A single synthetic row with deliberately long names in both scripts. */
function longAppointment(): Appointment {
  return {
    ...required(syntheticAlphaAppointments[0]),
    id: 'synthetic-long-name',
    patientName: { latinName: LONG_ENGLISH, localName: LONG_ARABIC },
  };
}

/** Render the worklist with deterministic media queries and synthetic data. */
function renderWorklist(
  band: Band,
  appointments: readonly Appointment[] = dayAppointments,
  locale: Locale = 'en',
): RenderResult {
  installMatchMedia(answers(band));
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <AppointmentWorklist
          appointments={appointments}
          range="day"
          onRangeChange={onRangeChange}
          onCheckIn={onCheckIn}
          onAssign={onAssign}
        />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** Style attribute of an element, used for structural (not painted) checks. */
function style(node: Element | null): string {
  return node?.getAttribute('style') ?? '';
}

const COLUMNS_EN = [
  'TIME',
  'PATIENT',
  'VISIT',
  'CLINICIAN AND ROOM',
  'STATE',
  'ASSIGNED',
  'ACTIONS',
];
const COLUMNS_AR = ['الوقت', 'المريض', 'الزيارة', 'الطبيب والغرفة', 'الحالة', 'المسند', 'الإجراءات'];

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive AppointmentWorklist (WEB-07, UX-01)', () => {
  it('shows compact rows as labelled stacked cards with every clinical field', () => {
    renderWorklist('compact');
    const caption = schedulingText('en', 'appointments');
    const region = screen.getByRole('region', { name: caption });
    expect(region.className).toContain('table-region');
    expect(region.tabIndex).toBe(0);
    expect(region.getAttribute('data-layout')).toBe('cards');

    const table = within(region).getByRole('table', { name: caption });
    expect(table.getAttribute('data-layout')).toBe('cards');
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((node) => node.textContent),
    ).toEqual(COLUMNS_EN);

    // The card class sits on tbody so the stylesheet's `.card-list > *` rule
    // gives every row a card border and padding; rows stay real table rows.
    const body = table.querySelector('tbody') as HTMLElement;
    expect(body.className).toContain('card-list');
    const cards = Array.from(body.querySelectorAll('tr'));
    expect(cards).toHaveLength(3);
    for (const card of cards) {
      expect(style(card)).toContain('flex-direction: column');
      expect(style(card)).toContain('padding-inline');
      // Every clinical cell stays in the card: none is hidden to save space.
      expect(card.querySelectorAll('th, td')).toHaveLength(7);
      for (const cell of card.querySelectorAll('th, td')) {
        expect(style(cell)).toContain('overflow-wrap: anywhere');
        expect(style(cell)).toContain('white-space: normal');
        expect(style(cell)).not.toContain('display: none');
      }
    }

    const first = required(cards[0]);
    expect(within(first).getByRole('rowheader').textContent).toContain('Synthetic Sara');
    expect(
      within(first)
        .getByRole('rowheader')
        .closest('tr')
        ?.querySelector('time')
        ?.getAttribute('datetime'),
    ).toBe('2026-03-09T08:15:00Z');
    // Time, patient, status and the next action are all in the same card.
    expect(within(first).getByText(schedulingText('en', 'stateInProgress'))).toBeTruthy();
    expect(
      within(first).getByRole('button', { name: schedulingText('en', 'openChart') }),
    ).toBeTruthy();
    expect(within(first).getByLabelText(schedulingText('en', 'assign'))).toBeTruthy();
  });

  it('uses a plain full table with no card treatment from medium upward', () => {
    for (const band of ['medium', 'expanded'] as const) {
      const view = renderWorklist(band);
      const caption = schedulingText('en', 'appointments');
      expect(screen.queryByRole('region', { name: caption })).toBeNull();
      const table = screen.getByRole('table', { name: caption });
      expect(table.getAttribute('data-layout')).toBe('table');
      expect(
        within(table)
          .getAllByRole('columnheader')
          .map((node) => node.textContent),
      ).toEqual(COLUMNS_EN);
      expect((table.querySelector('tbody') as HTMLElement).className).not.toContain('card-list');
      for (const cell of table.querySelectorAll('th, td')) {
        expect(style(cell)).not.toContain('display: none');
        expect(style(cell)).toContain('overflow-wrap: anywhere');
      }
      view.unmount();
    }
  });

  it('maps each supported width to the right card or table treatment', () => {
    const phones = [320, 360, 390];
    const tabletsAndDesktop = [768, 820, 1024, 1280];
    for (const width of [...phones, ...tabletsAndDesktop]) {
      const band = breakpointFor(width);
      const view = renderWorklist(band, [longAppointment()]);
      const caption = schedulingText('en', 'appointments');
      const table = screen.getByRole('table', { name: caption });
      expect(table.getAttribute('data-layout')).toBe(phones.includes(width) ? 'cards' : 'table');
      const region = screen.queryByRole('region', { name: caption });
      if (phones.includes(width)) {
        expect(region).toBeTruthy();
        expect((table.querySelector('tbody') as HTMLElement).className).toContain('card-list');
      } else {
        expect(region).toBeNull();
      }
      view.unmount();
    }
  });

  it('keeps the same keyboard order and row controls at each layout band', () => {
    for (const band of ['compact', 'medium', 'expanded'] as const) {
      const view = renderWorklist(band);
      const stops = Array.from(
        required(
          screen
            .getByRole('table', { name: schedulingText('en', 'appointments') })
            .closest('div[style]'),
        ).querySelectorAll('button, select, [tabindex="0"]'),
      );
      expect(stops.slice(0, 2).map((node) => node.textContent)).toEqual(['Day', 'Week']);
      expect(stops.slice(2, 5).map((node) => node.textContent)).toEqual([
        schedulingText('en', 'filterAll'),
        schedulingText('en', 'filterNeedsCheckIn'),
        schedulingText('en', 'filterWaiting'),
      ]);
      const controls = stops
        .slice(5)
        .filter((node) => node.tagName === 'BUTTON' || node.tagName === 'SELECT');
      expect(controls.map((node) => node.tagName)).toEqual([
        'SELECT',
        'BUTTON',
        'SELECT',
        'BUTTON',
        'SELECT',
        'BUTTON',
      ]);
      if (band === 'compact') {
        expect(stops[5]?.getAttribute('role')).toBe('region');
      } else {
        expect(stops.some((node) => node.getAttribute('role') === 'region')).toBe(false);
      }

      const checks = screen.getAllByRole('button', { name: schedulingText('en', 'checkIn') });
      expect(checks).toHaveLength(1);
      expect((checks[0] as HTMLButtonElement).disabled).toBe(false);
      fireEvent.click(required(checks[0]));
      expect(onCheckIn).toHaveBeenCalledWith('apt-001');

      const selects = screen.getAllByLabelText<HTMLSelectElement>(schedulingText('en', 'assign'));
      expect(selects).toHaveLength(3);
      expect(selects.every((select) => select.className.includes('field'))).toBe(true);
      expect(selects.every((select) => select.className.includes('touch'))).toBe(true);
      expect(selects.every((select) => select.style.fontSize === '16px')).toBe(true);
      fireEvent.change(required(selects[2]), { target: { value: 'synthetic.nurse' } });
      expect(onAssign).toHaveBeenCalledWith('apt-001', 'synthetic.nurse');
      view.unmount();
    }
  });

  it('keeps the range toggle reachable and preserves the defined empty state on compact', () => {
    const view = renderWorklist('compact', []);
    const caption = schedulingText('en', 'appointments');
    expect(screen.getByRole('region', { name: caption })).toBeTruthy();
    const table = screen.getByRole('table', { name: caption });
    expect(
      within(table).getByText(schedulingText('en', 'noAppointments')).getAttribute('colspan'),
    ).toBe('7');
    expect((table.querySelector('tbody') as HTMLElement).querySelectorAll('tr')).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'week') }));
    expect(onRangeChange).toHaveBeenCalledWith('week');
    fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'day') }));
    expect(onRangeChange).toHaveBeenCalledWith('day');
    view.unmount();

    renderWorklist('medium', []);
    expect(screen.getByRole('table', { name: caption })).toBeTruthy();
    expect(screen.getByText(schedulingText('en', 'noAppointments'))).toBeTruthy();
  });

  it('reflows from cards to a table and back when the viewport crosses a breakpoint', () => {
    const media = installMatchMedia(answers('compact'));
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <AppointmentWorklist
            appointments={dayAppointments}
            range="day"
            onRangeChange={onRangeChange}
            onCheckIn={onCheckIn}
            onAssign={onAssign}
          />
        </I18nProvider>
      </ThemeProvider>,
    );
    const caption = schedulingText('en', 'appointments');
    expect(screen.getByRole('region', { name: caption })).toBeTruthy();

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(screen.queryByRole('region', { name: caption })).toBeNull();
    expect(screen.getByRole('table', { name: caption }).getAttribute('data-layout')).toBe('table');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(screen.queryByRole('region', { name: caption })).toBeNull();

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, false));
    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, false));
    expect(screen.getByRole('region', { name: caption })).toBeTruthy();
    const cardTable = screen.getByRole('table', { name: caption });
    expect(cardTable.getAttribute('data-layout')).toBe('cards');
    expect((cardTable.querySelector('tbody') as HTMLElement).className).toContain('card-list');
  });

  it('wraps long English values at every band without clipping or a fixed block size', () => {
    for (const band of ['compact', 'expanded'] as const) {
      const view = renderWorklist(band, [longAppointment()]);
      const table = screen.getByRole('table', { name: schedulingText('en', 'appointments') });
      const rowheader = within(table).getByRole('rowheader');
      expect(rowheader.textContent).toContain(LONG_ENGLISH);
      expect(style(rowheader)).toContain('overflow-wrap: anywhere');
      expect(style(rowheader.querySelector('bdi'))).toContain('overflow-wrap: anywhere');
      expect(style(rowheader)).not.toContain('white-space: nowrap');
      expect(style(rowheader)).not.toContain('text-overflow');
      expect(style(rowheader)).not.toMatch(/\bheight\s*:/);
      view.unmount();
    }
  });

  it('mirrors under right-to-left with isolated Arabic runs and logical-only inline styles', () => {
    renderWorklist('compact', [longAppointment()], 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');

    const caption = schedulingText('ar', 'appointments');
    const region = screen.getByRole('region', { name: caption });
    expect(region.className).toContain('table-region');
    const table = within(region).getByRole('table', { name: caption });
    expect((table.querySelector('tbody') as HTMLElement).className).toContain('card-list');
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((node) => node.textContent),
    ).toEqual(COLUMNS_AR);
    for (const label of [
      schedulingText('ar', 'day'),
      schedulingText('ar', 'week'),
      schedulingText('ar', 'checkIn'),
      schedulingText('ar', 'assign'),
      schedulingText('ar', 'stateBooked'),
    ]) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }

    const rowheader = within(table).getByRole('rowheader');
    const bdi = rowheader.querySelector('bdi');
    expect(bdi?.textContent).toBe(LONG_ARABIC);
    expect(bdi?.getAttribute('dir')).toBe('auto');
    expect(bdi?.getAttribute('lang')).toBe('ar');

    // No component sets a physical side, a physical alignment or a direction
    // override: the document direction mirrors the logical stylesheet alone.
    for (const element of [region, ...Array.from(region.querySelectorAll('*'))] as HTMLElement[]) {
      const inline = style(element);
      expect(inline).not.toMatch(/(?:margin|padding|border)-(?:left|right|top|bottom)\s*:/);
      expect(inline).not.toMatch(/(?:left|right|top|bottom)\s*:/);
      expect(inline).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(inline).not.toMatch(/(?:^|;)\s*direction\s*:/);
      expect(inline).not.toContain('display: none');
    }
    expect(within(table).getByLabelText(schedulingText('ar', 'assign'))).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
