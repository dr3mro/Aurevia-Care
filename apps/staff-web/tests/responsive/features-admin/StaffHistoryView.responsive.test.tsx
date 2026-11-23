import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import {
  alphaOrganizationId,
  betaOrganizationId,
  syntheticStaffEvents,
} from '../../../src/features/admin/fixtures';
import { adminText } from '../../../src/features/admin/messages';
import { StaffHistoryView } from '../../../src/features/admin/StaffHistoryView';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

/** Widths from the responsive verification matrix, mapped to layout bands. */
const WIDTHS = [320, 360, 390, 768, 820, 1024, 1280] as const;
const COMPACT_WIDTHS = WIDTHS.filter((width) => width < BREAKPOINT_WIDTHS.medium);
const MEDIUM_WIDTHS = WIDTHS.filter(
  (width) => width >= BREAKPOINT_WIDTHS.medium && width < BREAKPOINT_WIDTHS.expanded,
);
const EXPANDED_WIDTHS = WIDTHS.filter((width) => width >= BREAKPOINT_WIDTHS.expanded);

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query string. One state
 * object is reused per query so the hook's initializer and effect see the same
 * listeners. All data below is synthetic.
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
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
      };
    }),
  );
  return {
    state,
    fire(query: string, matches: boolean) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

/** Install the two breakpoint queries for one synthetic viewport width. */
function installWidth(width: number) {
  return installMatchMedia({
    [BREAKPOINT_MEDIA_QUERIES.medium]: width >= BREAKPOINT_WIDTHS.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: width >= BREAKPOINT_WIDTHS.expanded,
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

/** Render the synthetic history view at a given width, tenant, subject and locale. */
function renderAt(
  width: number,
  organizationId: string = alphaOrganizationId,
  subject = 'synthetic.reception',
  locale: Locale = 'en',
) {
  const media = installWidth(width);
  const view = render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <StaffHistoryView
          events={syntheticStaffEvents}
          organizationId={organizationId}
          subject={subject}
          reasonLang={locale}
        />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...media, ...view };
}

/** The labelled region rendered inside the history view at any breakpoint. */
function historyRegion(): HTMLElement {
  const region = document.querySelector('[role="region"][tabindex="0"]');
  if (!region) {
    throw new Error('labelled history region with tabindex=0 was not rendered');
  }
  return region as HTMLElement;
}

/** The section carrying the active layout band. */
function breakpointRoot(): HTMLElement {
  const root = document.querySelector('section[data-breakpoint]');
  if (!root) {
    throw new Error('history section with data-breakpoint was not rendered');
  }
  return root as HTMLElement;
}

const COLUMNS = [
  'sequence',
  'subject',
  'event',
  'actor',
  'grantor',
  'role',
  'effectiveAtUtc',
  'deactivationReason',
] as const;

describe('responsive StaffHistoryView (WEB-07, UX-01)', () => {
  it('renders labelled cards with the full column set on every phone width', () => {
    for (const width of COMPACT_WIDTHS) {
      const { unmount } = renderAt(width);
      const root = breakpointRoot();
      expect(root.getAttribute('data-breakpoint')).toBe('compact');
      expect(root.className).toContain('page');

      const region = historyRegion();
      expect(region.getAttribute('aria-label')).toBe(adminText('en', 'staffHistory'));
      expect(region.className).toContain('card-list');
      expect(region.tabIndex).toBe(0);

      // Each append-only event becomes its own card table; table semantics and
      // the full eight-column set are preserved (decision 0004).
      const cards = Array.from(region.querySelectorAll('table')) as HTMLElement[];
      expect(cards).toHaveLength(2);
      for (const card of cards) {
        expect(card.querySelector('caption')?.textContent).toContain(adminText('en', 'sequence'));
        expect(
          within(card)
            .getAllByRole('rowheader')
            .map((cell) => cell.textContent),
        ).toEqual(COLUMNS.map((key) => adminText('en', key)));
        for (const cell of within(card).getAllByRole('cell')) {
          const style = cell.style;
          expect(style.display).not.toBe('none');
          expect(style.overflowWrap).toBe('anywhere');
          expect(style.whiteSpace).toBe('normal');
        }
      }
      expect(screen.queryByRole('columnheader')).toBeNull();
      unmount();
    }
  });

  it('keeps the defined empty state inside the card list on a phone', () => {
    renderAt(320, alphaOrganizationId, 'missing.subject');
    const region = historyRegion();
    expect(region.className).toContain('card-list');
    expect(within(region).getByText(adminText('en', 'noHistory'))).toBeTruthy();
    expect(region.querySelector('table')).toBeNull();
  });

  it('keeps a full table with no cards on every medium and expanded width', () => {
    for (const width of [...MEDIUM_WIDTHS, ...EXPANDED_WIDTHS]) {
      const { unmount } = renderAt(width);
      const expected = width >= BREAKPOINT_WIDTHS.expanded ? 'expanded' : 'medium';
      expect(breakpointRoot().getAttribute('data-breakpoint')).toBe(expected);

      const region = historyRegion();
      expect(region.className).not.toContain('card-list');
      expect(
        within(region)
          .getAllByRole('columnheader')
          .map((cell) => cell.textContent),
      ).toEqual(COLUMNS.map((key) => adminText('en', key)));
      expect(
        within(region)
          .getAllByRole('rowheader')
          .map((cell) => cell.textContent),
      ).toEqual(['1', '2']);
      for (const cell of within(region).getAllByRole('cell')) {
        expect(cell.style.display).not.toBe('none');
      }
      unmount();
    }
  });

  it('switches from cards to the table when the viewport crosses the compact edge', () => {
    const view = renderAt(360);
    expect(breakpointRoot().getAttribute('data-breakpoint')).toBe('compact');
    expect(historyRegion().className).toContain('card-list');

    act(() => view.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(breakpointRoot().getAttribute('data-breakpoint')).toBe('medium');
    expect(historyRegion().className).not.toContain('card-list');
    expect(screen.getAllByRole('columnheader')).toHaveLength(COLUMNS.length);

    act(() => view.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(breakpointRoot().getAttribute('data-breakpoint')).toBe('expanded');
  });

  it('keeps a single keyboard order: the labelled region is the only tab stop', () => {
    for (const width of [320, 768, 1280]) {
      const { unmount } = renderAt(width);
      expect(Array.from(document.querySelectorAll('[tabindex="0"]'))).toEqual([historyRegion()]);
      for (const cell of document.querySelectorAll('th, td')) {
        expect((cell as HTMLElement).tabIndex).toBe(-1);
      }
      unmount();
    }
  });

  it('shows the deactivation status once and keeps grantor and reason visible for every band', () => {
    for (const width of [320, 768, 1280]) {
      const { unmount } = renderAt(width, betaOrganizationId);
      const region = historyRegion();
      expect(
        within(region).getAllByText(adminText('en', 'eventDeactivated')).length,
      ).toBeGreaterThan(0);
      expect(within(region).getByText('Synthetic tenant offboarding')).toBeTruthy();
      expect(within(region).getByText('reception')).toBeTruthy();
      expect(screen.getByRole('status').textContent).toContain(adminText('en', 'eventDeactivated'));
      unmount();
    }
  });

  it('mirrors the Arabic RTL card layout without a direction override or physical styles', () => {
    renderAt(360, alphaOrganizationId, 'synthetic.auditor', 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(breakpointRoot().getAttribute('data-breakpoint')).toBe('compact');

    const region = historyRegion();
    expect(region.getAttribute('aria-label')).toBe(adminText('ar', 'staffHistory'));
    const firstCard = region.querySelector('table') as HTMLElement;
    expect(
      within(firstCard)
        .getAllByRole('rowheader')
        .map((cell) => cell.textContent),
    ).toEqual(COLUMNS.map((key) => adminText('ar', key)));
    expect(within(region).getByText(adminText('ar', 'eventDeactivated'))).toBeTruthy();
    expect(within(region).getByText('Synthetic access review closure')).toBeTruthy();

    const caption = region.querySelector('caption') as HTMLElement;
    expect(caption.style.textAlign).toBe('start');
    for (const element of [
      breakpointRoot(),
      region,
      ...Array.from(region.querySelectorAll('*')),
    ] as HTMLElement[]) {
      const style = element.getAttribute('style') ?? '';
      expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(element.style.direction).toBe('');
    }
    const latinRun = within(region).getByText('Synthetic access review closure');
    expect(latinRun.getAttribute('dir')).toBe('auto');
    expect(latinRun.getAttribute('lang')).toBe('ar');
  });

  it('wraps long English and Arabic clinical values without clipping them', () => {
    renderAt(390);
    const runs = Array.from(document.querySelectorAll<HTMLElement>('bdi'));
    expect(runs.length).toBeGreaterThan(0);
    for (const run of runs) {
      expect(run.getAttribute('dir')).toBe('auto');
      expect(run.style.overflowWrap).toBe('anywhere');
      expect(run.style.whiteSpace).toBe('normal');
    }
    expect(document.querySelector('time')?.getAttribute('datetime')).toBe('2026-01-05T08:00:00Z');
  });
});
