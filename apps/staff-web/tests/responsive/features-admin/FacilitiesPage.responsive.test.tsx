import type { ReactElement } from 'react';
import { act, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActiveOrganizationProvider } from '../../../src/api/activeOrganization';
import { send } from '../../../src/api/transport';
import type * as TransportModule from '../../../src/api/transport';
import type { FacilityPageResponse } from '../../../src/features/admin/api';
import { alphaOrganizationId } from '../../../src/features/admin/fixtures';
import { adminText } from '../../../src/features/admin/messages';
import { FacilitiesPage } from '../../../src/features/admin/FacilitiesPage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

vi.mock('../../../src/api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

const BREAKPOINTS: readonly Breakpoint[] = ['compact', 'medium', 'expanded'];
const COLUMN_KEYS = ['facilityCode', 'facilityName', 'timeZone'] as const;

const LONG_ENGLISH =
  'A deliberately long synthetic facility name that must wrap without clipping or overlap';
const LONG_ARABIC = 'اسم منشأة تجريبي طويل جداً يجب أن يلتف دون قص أو تداخل';

/**
 * Deterministic media-query stand-in. The responsive hooks read the two
 * `min-width` queries plus the coarse-pointer query; one state object per query
 * lets the initializer and the effect share listeners so a runtime width change
 * can be fired. All data below is synthetic.
 */
function installMatchMedia(breakpoint: Breakpoint, coarsePointer = false) {
  const initial: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: breakpoint !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: breakpoint === 'expanded',
    '(pointer: coarse)': coarsePointer,
    '(orientation: portrait)': true,
  };
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
    fire(query: string, matches: boolean) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

/** A synthetic facility row; no real identifiers. */
function facility(overrides: Partial<FacilityPageResponse['items'][number]> = {}) {
  return {
    organizationId: alphaOrganizationId,
    code: 'MAIN',
    name: 'Synthetic Main Facility',
    timeZone: 'UTC',
    ...overrides,
  };
}

function facilityPage(
  items: FacilityPageResponse['items'] = [facility()],
  nextCursor: string | null = null,
): FacilityPageResponse {
  return { items, nextCursor };
}

/** Render the page inside the providers that supply locale, tenant and tokens. */
function element(ui: ReactElement, locale: Locale = 'en') {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
          {ui}
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(locale: Locale = 'en') {
  return render(element(<FacilitiesPage />, locale));
}

/** The outer page wrapper is the render container's first element. */
function pageRoot(container: HTMLElement): HTMLElement {
  const root = container.firstElementChild;
  if (!root) {
    throw new Error('FacilitiesPage did not render a root element');
  }
  return root as HTMLElement;
}

/** The caller-owned fields wrapper inside the shared Form's fields part. */
function fieldsWrapper(page: HTMLElement): HTMLElement {
  const wrapper = page.querySelector('[data-part="fields"] > div');
  if (!wrapper) {
    throw new Error('facility fields wrapper was not rendered');
  }
  return wrapper as HTMLElement;
}

/** The labelled, keyboard-scrollable table region (not the ApiPage section). */
function tableRegion(page: HTMLElement, label: string): HTMLElement {
  const region = [...page.querySelectorAll('[role="region"]')].find(
    (element) =>
      element.getAttribute('aria-label') === label && element.getAttribute('tabindex') === '0',
  );
  if (!region) {
    throw new Error('labelled table scroll region was not rendered');
  }
  return region as HTMLElement;
}

beforeEach(() => {
  sendMock.mockReset();
  sendMock.mockResolvedValue(facilityPage());
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive FacilitiesPage (WEB-07, UX-01)', () => {
  it.each(BREAKPOINTS)(
    'renders one logical page with every facilities section at %s',
    async (breakpoint) => {
      installMatchMedia(breakpoint);
      const { container } = wrap();
      const page = pageRoot(container);

      expect(page.classList.contains('page')).toBe(true);
      expect(page.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(page.getAttribute('style')).toBeNull();

      const createSection = page.querySelector('section[aria-label]');
      expect(createSection?.classList.contains('stack')).toBe(true);
      expect(createSection?.getAttribute('style')).toBeNull();
      expect(createSection?.getAttribute('aria-label')).toBe(adminText('en', 'createFacility'));

      expect(within(page).getByRole('heading', { level: 2, name: adminText('en', 'facilities') })).toBeTruthy();
      expect(within(page).getByText(adminText('en', 'facilitiesIntro'))).toBeTruthy();

      const form = within(page).getByRole('form', { name: adminText('en', 'createFacility') });
      expect(fieldsWrapper(page).classList.contains(breakpoint === 'compact' ? 'stack' : 'grid-2')).toBe(true);
      expect(form.getAttribute('data-breakpoint')).toBe(breakpoint);

      // One DOM and one accessibility tree at every band: the labelled scroll
      // region never disappears and every API column stays reachable.
      const table = await screen.findByRole('table', { name: adminText('en', 'facilities') });
      const region = tableRegion(page, adminText('en', 'facilities'));
      expect(within(region).getByRole('table', { name: adminText('en', 'facilities') })).toBe(table);
      expect(table.getAttribute('data-breakpoint')).toBe(breakpoint);
      for (const key of COLUMN_KEYS) {
        const header = within(table).getByRole('columnheader', { name: adminText('en', key) }) as HTMLElement;
        expect(header.getAttribute('scope')).toBe('col');
        expect(header.style.display).not.toBe('none');
      }
      // The table commits with its headers in the same render that `useApi`
      // turns ready; the rows arrive one commit later from `PageSink`. Wait for
      // the row so a synchronous read cannot observe the empty tbody (WEB-07).
      expect(await within(table).findByRole('rowheader', { name: 'MAIN' })).toBeTruthy();
      expect(within(table).getByText('Synthetic Main Facility')).toBeTruthy();
      expect(within(table).getByText('UTC')).toBeTruthy();

      const observed = [...page.querySelectorAll('[data-breakpoint]')].map((element) =>
        element.getAttribute('data-breakpoint'),
      );
      expect(observed.length).toBeGreaterThan(1);
      expect(new Set(observed)).toEqual(new Set([breakpoint]));
    },
  );

  it.each(BREAKPOINTS)('keeps labels, roles and keyboard order at %s', async (breakpoint) => {
    installMatchMedia(breakpoint);
    const { container } = wrap();
    await screen.findByRole('table', { name: adminText('en', 'facilities') });

    const code = screen.getByLabelText(adminText('en', 'facilityCode')) as HTMLInputElement;
    const name = screen.getByLabelText(adminText('en', 'facilityName')) as HTMLInputElement;
    const submit = screen.getByRole('button', { name: adminText('en', 'createFacility') }) as HTMLElement;
    const status = screen.getByTestId('facilities-status');
    expect(status.getAttribute('role')).toBe('status');
    expect(status.hasAttribute('aria-label')).toBe(false);
    expect([code.id, name.id]).toEqual(['facility-code', 'facility-name']);
    expect(submit.getAttribute('type')).toBe('submit');

    const tabStops = [...container.querySelectorAll('[tabindex]')].map((element) =>
      Number(element.getAttribute('tabindex')),
    );
    expect(tabStops.every((index) => index >= 0)).toBe(true);
    expect(container.querySelectorAll('[role="region"][tabindex="0"]')).toHaveLength(1);
    const focusable = [...container.querySelectorAll('button, input, [tabindex="0"]')];
    expect(focusable.indexOf(code)).toBeLessThan(focusable.indexOf(name));
    expect(focusable.indexOf(name)).toBeLessThan(focusable.indexOf(submit));
    for (const stop of focusable) {
      expect((stop as HTMLElement).getAttribute('disabled')).toBeNull();
    }
  });

  it('gives coarse pointers touch targets, 16px inputs and every column on a phone', async () => {
    installMatchMedia('compact', true);
    wrap();
    await screen.findByRole('table', { name: adminText('en', 'facilities') });

    for (const key of ['facilityCode', 'facilityName'] as const) {
      const input = screen.getByLabelText(adminText('en', key)) as HTMLInputElement;
      expect(input.classList.contains('field')).toBe(true);
      expect(input.classList.contains('touch')).toBe(true);
    }
    const submit = screen.getByRole('button', { name: adminText('en', 'createFacility') }) as HTMLElement;
    expect(submit.classList.contains('touch')).toBe(true);
    expect(submit.closest('[data-part="actions"]')?.classList.contains('touch')).toBe(true);

    // The `field` class in responsive.css owns the 16px input text; no clinical
    // column is hidden to make the narrow layout fit.
    for (const header of screen.getAllByRole('columnheader')) {
      expect((header as HTMLElement).style.display).not.toBe('none');
    }
  });

  it('wraps long English and Arabic values with logical-only styles under right-to-left', async () => {
    installMatchMedia('compact');
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    sendMock.mockResolvedValue(
      facilityPage([
        facility({ code: 'SYN-LONG-01', name: LONG_ENGLISH, timeZone: 'UTC' }),
        facility({ code: 'SYN-LONG-02', name: LONG_ARABIC, timeZone: 'Asia/Riyadh' }),
      ]),
    );
    const { container } = wrap('ar');
    const table = await screen.findByRole('table', { name: adminText('ar', 'facilities') });

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(within(table).getByText(LONG_ENGLISH)).toBeTruthy();
    expect(within(table).getByText(LONG_ARABIC)).toBeTruthy();

    for (const run of document.querySelectorAll('bdi')) {
      const isolated = run as HTMLElement;
      expect(isolated.getAttribute('dir')).toBe('auto');
      expect(isolated.hasAttribute('lang')).toBe(true);
      expect(isolated.style.overflowWrap).toBe('anywhere');
      expect(isolated.style.whiteSpace).toBe('normal');
      expect(isolated.style.minInlineSize).toBe('0');
    }

    for (const element of [...container.querySelectorAll<HTMLElement>('[style]')]) {
      expect(element.style.direction).toBe('');
      expect(element.style.display).not.toBe('none');
      const style = element.getAttribute('style') ?? '';
      expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(style).not.toMatch(/(?:^|;)\s*(?:left|right)\s*:/);
    }
  });

  it('follows a width change without losing the page wrapper or the rendered data', async () => {
    const media = installMatchMedia('compact');
    const { container } = wrap();
    await screen.findByRole('table', { name: adminText('en', 'facilities') });
    let page = pageRoot(container);
    expect(page.getAttribute('data-breakpoint')).toBe('compact');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    page = pageRoot(container);
    expect(page.classList.contains('page')).toBe(true);
    expect(page.getAttribute('data-breakpoint')).toBe('medium');
    expect(screen.getByText('Synthetic Main Facility')).toBeTruthy();
    expect(screen.getByLabelText(adminText('en', 'facilityCode'))).toBeTruthy();
    expect(fieldsWrapper(page).classList.contains('grid-2')).toBe(true);
  });

  it('shows the facility row after a deferred list response commits the table first', async () => {
    installMatchMedia('compact');
    // Defer the transport so the table (with its headers) commits before the
    // row is appended by the list's post-commit sink. All data is synthetic.
    sendMock.mockImplementation(
      () => new Promise((resolve) => setTimeout(() => resolve(facilityPage()), 0)),
    );
    wrap();
    const table = await screen.findByRole('table', { name: adminText('en', 'facilities') });

    // The deferred response makes the two commits explicit: the table is queryable
    // before its rows exist. Waiting for the row proves the end state still reaches
    // the user without depending on how fast the effect that appends it runs.
    expect(await within(table).findByRole('rowheader', { name: 'MAIN' })).toBeTruthy();
    expect(within(table).getByText('Synthetic Main Facility')).toBeTruthy();
    expect(within(table).getByText('UTC')).toBeTruthy();
  });
});

describe('responsive FacilitiesPage width matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  it.each(widths)('maps %ipx to the correct layout band', (width) => {
    const band: Breakpoint =
      width >= BREAKPOINT_WIDTHS.expanded
        ? 'expanded'
        : width >= BREAKPOINT_WIDTHS.medium
          ? 'medium'
          : 'compact';
    installMatchMedia(band);
    const { container } = wrap();
    expect(pageRoot(container).getAttribute('data-breakpoint')).toBe(band);
  });
});
