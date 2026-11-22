import type { ReactElement } from 'react';
import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ChartScreen } from '../../../src/features/chart/ChartScreen';
import { alphaOrganizationId, syntheticCharts } from '../../../src/features/chart/fixtures';
import { chartText } from '../../../src/features/chart/messages';
import type { Locale } from '../../../src/i18n/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import { BREAKPOINT_MEDIA_QUERIES, breakpointFor } from '../../../src/theme/breakpoints';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

/**
 * All supported viewport widths from the responsive contract, expressed only
 * through the breakpoint resolver: jsdom cannot lay out pixels, so the test
 * asserts the structure the screen renders for each band.
 */
const VIEWPORT_WIDTHS = [320, 360, 390, 768, 820, 1024, 1280] as const;

/** Install a deterministic matchMedia stand-in for one layout band. */
function installMatchMedia(band: Breakpoint): void {
  const matches: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: matches[query] ?? false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    })),
  );
}

type ChangeListener = (event: MediaQueryListEvent) => void;

/**
 * Install a matchMedia stand-in that can announce a breakpoint change. The
 * hook subscribes to each query, so firing a change mirrors a real resize.
 */
function installLiveMatchMedia(initial: Breakpoint): (band: Breakpoint) => void {
  const matches: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: initial !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: initial === 'expanded',
  };
  const listeners = new Map<string, Set<ChangeListener>>();
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      get matches() {
        return matches[query] ?? false;
      },
      media: query,
      addEventListener: (_type: string, listener: ChangeListener) => {
        const current = listeners.get(query) ?? new Set<ChangeListener>();
        current.add(listener);
        listeners.set(query, current);
      },
      removeEventListener: (_type: string, listener: ChangeListener) => {
        listeners.get(query)?.delete(listener);
      },
    })),
  );
  return (band: Breakpoint) => {
    const next: Record<string, boolean> = {
      [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
      [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
    };
    for (const [query, value] of Object.entries(next)) {
      if (matches[query] === value) {
        continue;
      }
      matches[query] = value;
      for (const listener of listeners.get(query) ?? []) {
        listener({ matches: value } as MediaQueryListEvent);
      }
    }
  };
}

/** Wrap a view in the providers that supply locale direction and layout tokens. */
function wrap(view: ReactElement, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{view}</I18nProvider>
    </ThemeProvider>,
  );
}

/** Render the synthetic chart screen for one tenant. */
function renderChart(locale: Locale = 'en', organizationId = alphaOrganizationId) {
  return wrap(<ChartScreen charts={syntheticCharts} organizationId={organizationId} />, locale);
}

/** Return the labelled outermost page region of the screen. */
function pageRegion(locale: Locale): HTMLElement {
  return screen.getByRole('region', { name: chartText(locale, 'chart') }) as HTMLElement;
}

/** Every inline style below must be direction-neutral; physical sides break RTL. */
function assertLogicalStyles(region: HTMLElement): void {
  for (const element of [region, ...Array.from(region.querySelectorAll('*'))] as HTMLElement[]) {
    const style = element.getAttribute('style') ?? '';
    expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
    expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
    expect(element.style.direction).toBe('');
    expect(element.style.display).not.toBe('none');
  }
}

/** Assert that a clinical table kept its full, unhidden column set. */
function assertFullTable(name: string, expectedHeaders: number): void {
  const table = screen.getByRole('table', { name }) as HTMLElement;
  expect(within(table).getAllByRole('columnheader')).toHaveLength(expectedHeaders);
  for (const cell of within(table).getAllByRole('cell')) {
    expect((cell as HTMLElement).style.display).not.toBe('none');
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive ChartScreen (WEB-07, UX-01)', () => {
  it.each(VIEWPORT_WIDTHS)(
    'keeps one labelled page and every clinical value at %i px',
    (width) => {
      const band = breakpointFor(width);
      installMatchMedia(band);
      renderChart();

      const region = pageRegion('en');
      expect(region.classList.contains('page')).toBe(true);
      expect(region.getAttribute('data-breakpoint')).toBe(band);
      expect(region.getAttribute('aria-label')).toBe(chartText('en', 'chart'));

      const content = region.querySelector('[data-part="chart-content"]') as HTMLElement;
      expect(content).toBeTruthy();
      expect(content.className).toBe(band === 'compact' ? 'stack' : 'grid-2');
      expect(content.getAttribute('data-layout')).toBe(
        band === 'compact' ? 'single-column' : 'two-column',
      );

      expect(
        screen.getByRole('heading', { level: 2, name: chartText('en', 'chart') }),
      ).toBeTruthy();
      expect(screen.getByRole('status', { name: chartText('en', 'status') })).toBeTruthy();
      for (const key of ['problems', 'allergies', 'medications', 'vitals'] as const) {
        assertFullTable(chartText('en', key), key === 'vitals' ? 3 : 8);
      }
      assertFullTable(chartText('en', 'documentList'), 9);

      assertLogicalStyles(region);
    },
  );

  it('stacks the chart and note in labelled scroll regions on a compact phone', () => {
    installMatchMedia('compact');
    renderChart();

    const region = pageRegion('en');
    const content = region.querySelector('[data-part="chart-content"]') as HTMLElement;
    expect(content.className).toBe('stack');
    expect(content.getAttribute('data-layout')).toBe('single-column');

    const longitudinal = content.querySelector('[data-part="longitudinal"]') as HTMLElement;
    const note = content.querySelector('[data-part="note"]') as HTMLElement;
    const documents = region.querySelector('[data-part="documents"]') as HTMLElement;
    for (const scroller of [longitudinal, documents]) {
      expect(scroller.classList.contains('table-region')).toBe(true);
      expect(scroller.getAttribute('role')).toBe('region');
      expect(scroller.tabIndex).toBe(0);
      expect(scroller.style.minInlineSize).toBe('0px');
      expect(scroller.style.overflowWrap).toBe('anywhere');
    }
    expect(longitudinal.contains(screen.getByRole('table', { name: chartText('en', 'vitals') }))).toBe(
      true,
    );
    expect(documents.contains(screen.getByRole('table', { name: chartText('en', 'documentList') }))).toBe(
      true,
    );
    expect(note.contains(screen.getByRole('textbox', { name: chartText('en', 'plan') }))).toBe(true);

    // Keyboard order follows the DOM: the two labelled scroll regions bracket
    // the note fields and actions, and no control is removed at this width.
    const focusables = [
      ...region.querySelectorAll('[tabindex="0"], textarea, input, button'),
    ] as HTMLElement[];
    const documentsIndex = focusables.indexOf(documents);
    expect(focusables[0]).toBe(longitudinal);
    expect(documentsIndex).toBeGreaterThan(0);
    expect(focusables.slice(1, documentsIndex).map((element) => element.tagName)).toEqual([
      'TEXTAREA',
      'TEXTAREA',
      'TEXTAREA',
      'TEXTAREA',
      'BUTTON',
      'BUTTON',
    ]);
    // The document row actions stay reachable after the scroll region: no
    // action is dropped to make the narrow layout fit.
    expect(focusables.length).toBeGreaterThan(documentsIndex + 1);
    for (const element of focusables) {
      expect(element.tabIndex).toBeGreaterThanOrEqual(0);
    }
  });

  it.each(['medium', 'expanded'] as const)(
    'places the chart and note side by side on %s without hiding a field',
    (band) => {
      installMatchMedia(band);
      renderChart();

      const region = pageRegion('en');
      const content = region.querySelector('[data-part="chart-content"]') as HTMLElement;
      expect(content.className).toBe('grid-2');
      expect(content.getAttribute('data-layout')).toBe('two-column');
      for (const field of ['subjective', 'objective', 'assessment', 'plan'] as const) {
        expect(screen.getByRole('textbox', { name: chartText('en', field) })).toBeTruthy();
      }
      expect(screen.getByRole('button', { name: chartText('en', 'amend') })).toBeTruthy();
      expect(screen.getByRole('button', { name: chartText('en', 'markEnteredInError') })).toBeTruthy();
      for (const key of ['problems', 'allergies', 'medications', 'vitals'] as const) {
        assertFullTable(chartText('en', key), key === 'vitals' ? 3 : 8);
      }
      assertLogicalStyles(region);
    },
  );

  it('keeps Arabic runs, logical wrapping and the same structure under right-to-left', () => {
    installMatchMedia('compact');
    renderChart('ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const region = pageRegion('ar');
    expect(region.classList.contains('page')).toBe(true);
    expect(region.getAttribute('data-breakpoint')).toBe('compact');
    expect(
      screen.getByRole('heading', { level: 2, name: chartText('ar', 'chart') }),
    ).toBeTruthy();

    const content = region.querySelector('[data-part="chart-content"]') as HTMLElement;
    expect(content.getAttribute('data-layout')).toBe('single-column');
    const name = screen.getAllByText('ليلى تجريبية')[0] as HTMLElement;
    expect(name.getAttribute('dir')).toBe('auto');
    expect(name.getAttribute('lang')).toBe('ar');
    for (const scroller of [
      content.querySelector('[data-part="longitudinal"]') as HTMLElement,
      content.querySelector('[data-part="note"]') as HTMLElement,
      region.querySelector('[data-part="documents"]') as HTMLElement,
    ]) {
      expect(scroller.style.minInlineSize).toBe('0px');
      expect(scroller.style.overflowWrap).toBe('anywhere');
    }
    for (const key of ['subjective', 'objective', 'assessment', 'plan'] as const) {
      expect(screen.getByRole('textbox', { name: chartText('ar', key) })).toBeTruthy();
    }
    for (const key of ['problems', 'allergies', 'medications', 'vitals', 'documentList'] as const) {
      assertFullTable(chartText('ar', key), key === 'vitals' ? 3 : key === 'documentList' ? 9 : 8);
    }
    expect(name.textContent).toBe('ليلى تجريبية');
    assertLogicalStyles(region);
  });

  it('keeps the concealment-safe empty states inside the page wrapper', () => {
    installMatchMedia('compact');
    wrap(<ChartScreen charts={syntheticCharts} organizationId="unknown" />);
    expect(screen.getByText(chartText('en', 'noChart'))).toBeTruthy();
    expect(pageRegion('en').classList.contains('page')).toBe(true);
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('reflows across a breakpoint without dropping the note or its fields', () => {
    const setBand = installLiveMatchMedia('compact');
    renderChart();
    const content = pageRegion('en').querySelector('[data-part="chart-content"]') as HTMLElement;
    expect(content.getAttribute('data-layout')).toBe('single-column');

    act(() => setBand('expanded'));
    expect(pageRegion('en').getAttribute('data-breakpoint')).toBe('expanded');
    expect(content.getAttribute('data-layout')).toBe('two-column');
    expect(content.className).toBe('grid-2');
    for (const field of ['subjective', 'objective', 'assessment', 'plan'] as const) {
      expect(screen.getByRole('textbox', { name: chartText('en', field) })).toBeTruthy();
    }
  });
});
