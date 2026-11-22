import { act, render, screen, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LongitudinalChart } from '../../../src/features/chart/LongitudinalChart';
import type { Chart } from '../../../src/features/chart/domain';
import { syntheticAlphaChart } from '../../../src/features/chart/fixtures';
import { chartText } from '../../../src/features/chart/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

/** Layout band under test; jsdom asserts structure, never painted pixels. */
type Band = 'compact' | 'medium' | 'expanded';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/** Synthetic long values that must wrap, in both scripts, on a narrow phone. */
const LONG_ENGLISH =
  'A deliberately long synthetic clinical display name that must wrap without clipping or overlap on a narrow phone';
const LONG_ARABIC = 'اسم سريري تجريبي طويل جداً يجب أن يلتف دون قص أو تداخل على شاشة الهاتف';

const CODED_KEYS = ['problems', 'allergies', 'medications'] as const;

const CODED_HEADER_KEYS = [
  'code',
  'originalText',
  'display',
  'verification',
  'source',
  'effectiveOn',
  'clinicalAuthor',
  'enteredBy',
] as const;

/**
 * Deterministic matchMedia stand-in with one stable state per media query, so
 * the hook's initializer and effect share listeners. All data is synthetic.
 */
function installMatchMedia(band: Band) {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
  };
  const states = new Map<string, QueryState>();
  const state = (query: string): QueryState => {
    let current = states.get(query);
    if (!current) {
      current = { matches: answers[query] ?? false, listeners: new Set() };
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

/** Render the chart through its normal providers with a synthetic fixture. */
function renderChart(locale: Locale = 'en', chart: Chart = syntheticAlphaChart) {
  const ui: ReactElement = (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <LongitudinalChart chart={chart} locale={locale} />
      </I18nProvider>
    </ThemeProvider>
  );
  return render(ui);
}

/** A synthetic chart whose coded display value is deliberately long in both scripts. */
function longChart(): Chart {
  const first = syntheticAlphaChart.problems[0];
  if (!first) {
    throw new Error('missing synthetic problem fixture');
  }
  return {
    ...syntheticAlphaChart,
    problems: [{ ...first, display: LONG_ENGLISH, displayLocal: LONG_ARABIC }],
  };
}

/** Expected clinical column set: every column stays present at every width. */
function codedHeaders(locale: Locale): string[] {
  return CODED_HEADER_KEYS.map((key) => chartText(locale, key));
}

/** Long values wrap instead of clipping or forcing a page-wide scroll. */
function expectWrapping(elements: HTMLElement[]): void {
  for (const element of elements) {
    expect(element.style.overflowWrap).toBe('anywhere');
    expect(element.style.whiteSpace).toBe('normal');
    expect(element.getAttribute('style') ?? '').toContain('min-inline-size');
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive LongitudinalChart (WEB-07, UX-01)', () => {
  it('compact: wraps coded tables in labelled scroll regions and stacks the timeline with dates on their own line', () => {
    installMatchMedia('compact');
    renderChart('en', longChart());

    const section = screen.getByRole('region', { name: chartText('en', 'longitudinalChart') });
    expect(section.getAttribute('data-breakpoint')).toBe('compact');

    for (const key of CODED_KEYS) {
      const label = chartText('en', key);
      const region = screen.getByRole('region', { name: label }) as HTMLElement;
      expect(region.className).toContain('table-region');
      expect(region.getAttribute('tabindex')).toBe('0');
      expect(region.tabIndex).toBe(0);
      const table = within(region).getByRole('table', { name: label });
      const headers = within(table).getAllByRole('columnheader');
      expect(headers.map((node) => node.textContent)).toEqual(codedHeaders('en'));
      for (const header of headers) {
        expect(header.getAttribute('scope')).toBe('col');
      }
      expectWrapping([...headers, ...within(table).getAllByRole('cell')] as HTMLElement[]);
    }
    expect(screen.getByText(LONG_ENGLISH)).toBeTruthy();

    const timeline = screen.getByRole('list', { name: chartText('en', 'vitals') }) as HTMLElement;
    expect(timeline.className).toContain('card-list');
    expect(timeline.getAttribute('data-layout')).toBe('single-column');
    const cards = within(timeline).getAllByRole('listitem');
    expect(cards).toHaveLength(syntheticAlphaChart.vitals.length);
    for (const card of cards) {
      const time = card.querySelector('time') as HTMLElement | null;
      expect(time).not.toBeNull();
      // A block-level date is on its own line beneath the observation.
      expect(time?.style.display).toBe('block');
    }
  });

  it('compact: the only tab stops are the labelled scroll regions in clinical order', () => {
    installMatchMedia('compact');
    renderChart();

    const stops = Array.from(document.querySelectorAll('[tabindex="0"]')) as HTMLElement[];
    expect(stops.map((stop) => stop.getAttribute('aria-label'))).toEqual(
      CODED_KEYS.map((key) => chartText('en', key)),
    );
  });

  it.each(['medium', 'expanded'] as const)(
    '%s: keeps every clinical column in tables without compact regions or cards',
    (band) => {
      installMatchMedia(band);
      renderChart();

      const section = screen.getByRole('region', { name: chartText('en', 'longitudinalChart') });
      expect(section.getAttribute('data-breakpoint')).toBe(band);

      for (const key of CODED_KEYS) {
        const label = chartText('en', key);
        expect(screen.queryByRole('region', { name: label })).toBeNull();
        const table = screen.getByRole('table', { name: label });
        expect(within(table).getAllByRole('columnheader').map((node) => node.textContent)).toEqual(
          codedHeaders('en'),
        );
        for (const cell of within(table).getAllByRole('cell')) {
          expect((cell as HTMLElement).style.display).not.toBe('none');
        }
      }

      expect(screen.queryByRole('list', { name: chartText('en', 'vitals') })).toBeNull();
      const vitals = screen.getByRole('table', { name: chartText('en', 'vitals') });
      expect(within(vitals).getAllByRole('columnheader').map((node) => node.textContent)).toEqual([
        chartText('en', 'kind'),
        chartText('en', 'measurement'),
        chartText('en', 'measuredAt'),
      ]);
    },
  );

  it('compact: keeps the three column names with each vital for screen readers', () => {
    installMatchMedia('compact');
    renderChart();

    const timeline = screen.getByRole('list', { name: chartText('en', 'vitals') });
    for (const card of within(timeline).getAllByRole('listitem')) {
      for (const key of ['kind', 'measurement', 'measuredAt'] as const) {
        expect(within(card).getByText(chartText('en', key))).toBeTruthy();
      }
    }
  });

  it('medium: keeps every column and logical-only styles under right-to-left', () => {
    installMatchMedia('medium');
    renderChart('ar');

    expect(document.documentElement.dir).toBe('rtl');
    const table = screen.getByRole('table', { name: chartText('ar', 'problems') });
    expect(within(table).getAllByRole('columnheader').map((node) => node.textContent)).toEqual(
      codedHeaders('ar'),
    );
    for (const element of [table, ...Array.from(table.querySelectorAll('*'))] as HTMLElement[]) {
      const style = element.getAttribute('style') ?? '';
      expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      expect(element.style.direction).toBe('');
      expect(style).not.toContain('display: none');
    }
  });

  it('drops compact regions and cards when the viewport grows past compact without remounting', () => {
    const media = installMatchMedia('compact');
    renderChart();
    expect(screen.getByRole('region', { name: chartText('en', 'problems') })).toBeTruthy();

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));

    expect(screen.queryByRole('region', { name: chartText('en', 'problems') })).toBeNull();
    expect(screen.getByRole('table', { name: chartText('en', 'vitals') })).toBeTruthy();
  });

  it('compact: keeps a defined empty state for coded entries and the timeline', () => {
    installMatchMedia('compact');
    renderChart('en', {
      ...syntheticAlphaChart,
      problems: [],
      allergies: [],
      medications: [],
      vitals: [],
    });

    expect(screen.getAllByText(chartText('en', 'noEntries'))).toHaveLength(4);
    expect(screen.getByRole('list', { name: chartText('en', 'vitals') })).toBeTruthy();
  });

  it('right-to-left: mirrors with logical-only styles and keeps long Arabic values readable', () => {
    installMatchMedia('compact');
    renderChart('ar', longChart());

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(
      screen.getByRole('region', { name: chartText('ar', 'longitudinalChart') }),
    ).toBeTruthy();
    expect(screen.getByText(LONG_ARABIC)).toBeTruthy();
    const arabicCell = (screen.getByText(LONG_ARABIC) as HTMLElement).closest('td') as HTMLElement;
    expect(arabicCell.style.overflowWrap).toBe('anywhere');

    const region = screen.getByRole('region', { name: chartText('ar', 'problems') }) as HTMLElement;
    const timeline = screen.getByRole('list', { name: chartText('ar', 'vitals') }) as HTMLElement;
    expect(region.className).toContain('table-region');
    expect(timeline.className).toContain('card-list');
    // No component sets a physical side, a physical alignment, a direction
    // override or a hidden clinical cell: logical CSS mirrors on its own.
    for (const root of [region, timeline]) {
      for (const element of [root, ...Array.from(root.querySelectorAll('*'))] as HTMLElement[]) {
        const style = element.getAttribute('style') ?? '';
        expect(style).not.toMatch(/(?:margin|padding|border)-(?:left|right)\s*:/);
        expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
        expect(element.style.direction).toBe('');
        expect(style).not.toContain('display: none');
      }
    }
  });
});
