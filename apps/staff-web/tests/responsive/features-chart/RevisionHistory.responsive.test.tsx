import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { NoteRevision } from '../../../src/features/chart/domain';
import { syntheticAlphaChart, syntheticBetaChart } from '../../../src/features/chart/fixtures';
import { chartText } from '../../../src/features/chart/messages';
import { RevisionHistory } from '../../../src/features/chart/RevisionHistory';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

type Band = 'compact' | 'medium' | 'expanded';

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
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
      } as unknown as MediaQueryList;
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

/** Synthetic viewport states mirroring the responsive Playwright matrix. */
const BANDS: Record<Band, { medium: boolean; expanded: boolean }> = {
  compact: { medium: false, expanded: false },
  medium: { medium: true, expanded: false },
  expanded: { medium: true, expanded: true },
};

function bandQueries(band: Band): Record<string, boolean> {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: BANDS[band].medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: BANDS[band].expanded,
  };
}

function widthQueries(width: number): Record<string, boolean> {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: width >= BREAKPOINT_WIDTHS.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: width >= BREAKPOINT_WIDTHS.expanded,
  };
}

const signedWithError = syntheticAlphaChart.revisions[0] as NoteRevision;
const signedClean = syntheticAlphaChart.revisions[1] as NoteRevision;
const draftRevision = syntheticBetaChart.revisions[1] as NoteRevision;

const historyRevisions: readonly NoteRevision[] = [signedWithError, signedClean, draftRevision];

function renderHistory(
  revisions: readonly NoteRevision[] = historyRevisions,
  locale: 'en' | 'ar' = 'en',
) {
  return render(
    <ThemeProvider>
      <RevisionHistory revisions={revisions} locale={locale} />
    </ThemeProvider>,
  );
}

function historyRoot(): HTMLElement {
  const root = document.querySelector('[data-breakpoint]');
  if (!root) {
    throw new Error('responsive revision history root with data-breakpoint was not rendered');
  }
  return root as HTMLElement;
}

/** Physical inline styles are defects: logical properties mirror under RTL. */
const physicalStyleKeys = [
  'marginLeft',
  'marginRight',
  'paddingLeft',
  'paddingRight',
  'borderLeft',
  'borderRight',
  'left',
  'right',
  'top',
  'bottom',
];

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive RevisionHistory at %s width (WEB-07, UX-01)',
  (band) => {
    it('lists revisions as cards on compact and as an unhidden list otherwise', () => {
      installMatchMedia(bandQueries(band));
      renderHistory();

      const root = historyRoot();
      expect(root.tagName).toBe('OL');
      expect(root.getAttribute('data-breakpoint')).toBe(band);
      expect(root.getAttribute('data-revision-view')).toBe(band === 'compact' ? 'cards' : 'list');
      expect(root.classList.contains('card-list')).toBe(band === 'compact');

      const list = screen.getByRole('list', { name: chartText('en', 'history') });
      expect(list).toBe(root);
      const items = within(list).getAllByRole('listitem');
      expect(items).toHaveLength(historyRevisions.length);
      expect(items.map((item) => item.getAttribute('data-revision-id'))).toEqual(
        historyRevisions.map((revision) => revision.id),
      );
      for (const item of items) {
        // No clinical revision is removed or hidden at any breakpoint.
        expect((item as HTMLElement).style.display).not.toBe('none');
        expect((item as HTMLElement).hidden).toBe(false);
      }
    });

    it('keeps every revision state and the entered-in-error marker with wrapping text', () => {
      installMatchMedia(bandQueries(band));
      renderHistory();

      expect(screen.getByText(chartText('en', 'noteDraft'), { exact: false })).toBeTruthy();
      expect(screen.getAllByText(chartText('en', 'noteSigned'), { exact: false }).length).toBe(2);
      const marker = screen.getByText(chartText('en', 'enteredInError'));
      expect(marker.textContent).toBe(chartText('en', 'enteredInError'));
      expect(marker.getAttribute('style')).toContain('padding-inline');

      // Long clinical strings wrap instead of clipping or forcing a
      // viewport-wide scroll. jsdom checks the CSS contract, not painted pixels.
      for (const item of historyRoot().querySelectorAll('li')) {
        expect((item as HTMLElement).style.overflowWrap).toBe('anywhere');
        expect((item as HTMLElement).style.whiteSpace).toBe('normal');
        expect((item as HTMLElement).style.minInlineSize.length).toBeGreaterThan(0);
      }
    });

    it('keeps DOM keyboard order and adds no new tab stops', () => {
      installMatchMedia(bandQueries(band));
      renderHistory();

      const root = historyRoot();
      const items = screen.getAllByRole('listitem');
      expect(Array.from(root.querySelectorAll('li'))).toEqual(items);
      for (const item of items) {
        expect((item as HTMLElement).tabIndex).toBe(-1);
      }
      // This read-only history adds no controls, inputs or links, so it
      // introduces no tab stops that could trap keyboard focus on a phone.
      expect(root.querySelector('button, input, select, textarea, a, [tabindex]')).toBeNull();
    });

    it('uses logical inline styles only and hides no clinical content', () => {
      installMatchMedia(bandQueries(band));
      renderHistory();

      const root = historyRoot();
      const styled = Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root);
      for (const element of styled) {
        expect(element.style.direction).toBe('');
        expect(element.style.display).not.toBe('none');
        for (const key of physicalStyleKeys) {
          expect(element.style.getPropertyValue(key)).toBe('');
        }
      }
      expect(root.querySelector('.hide-compact')).toBeNull();
      expect(root.querySelector('.only-compact')).toBeNull();
      // The list band keeps the original grid; the card band relies on the
      // stylesheet's .card-list class instead of inline layout.
      if (band === 'compact') {
        expect(root.style.display).toBe('');
      } else {
        expect(root.style.display).toBe('grid');
      }
    });

    it('keeps the defined empty state with wrapping text', () => {
      installMatchMedia(bandQueries(band));
      renderHistory([]);

      const empty = screen.getByText(chartText('en', 'noEntries'));
      expect(empty.tagName).toBe('P');
      expect((empty as HTMLElement).style.overflowWrap).toBe('anywhere');
      expect((empty as HTMLElement).style.whiteSpace).toBe('normal');
      expect(screen.queryByRole('list')).toBeNull();
    });
  },
);

describe('responsive RevisionHistory viewport matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  it.each(widths)('resolves %ipx to the correct layout band', (width) => {
    installMatchMedia(widthQueries(width));
    renderHistory();

    const expected: Band =
      width >= BREAKPOINT_WIDTHS.expanded
        ? 'expanded'
        : width >= BREAKPOINT_WIDTHS.medium
          ? 'medium'
          : 'compact';
    const root = historyRoot();
    expect(root.getAttribute('data-breakpoint')).toBe(expected);
    expect(root.getAttribute('data-revision-view')).toBe(expected === 'compact' ? 'cards' : 'list');
  });
});

describe('responsive RevisionHistory right-to-left and reflow', () => {
  it('mirrors Arabic revision text with logical-only wrapping and no direction override', () => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    installMatchMedia(bandQueries('compact'));
    renderHistory(historyRevisions, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const root = historyRoot();
    expect(root.getAttribute('data-breakpoint')).toBe('compact');
    expect(screen.getByRole('list', { name: chartText('ar', 'history') })).toBe(root);
    expect(screen.getByText(chartText('ar', 'noteDraft'), { exact: false })).toBeTruthy();
    expect(screen.getByText(chartText('ar', 'enteredInError'))).toBeTruthy();

    for (const element of Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root)) {
      expect(element.style.direction).toBe('');
      expect(element.style.display).not.toBe('none');
      for (const key of physicalStyleKeys) {
        expect(element.style.getPropertyValue(key)).toBe('');
      }
    }
    for (const item of root.querySelectorAll('li')) {
      expect((item as HTMLElement).style.overflowWrap).toBe('anywhere');
      expect((item as HTMLElement).style.whiteSpace).toBe('normal');
    }
    // The Arabic label is isolated in the document direction; the component
    // never sets a physical side or a direction override of its own.
    expect(screen.getByText(chartText('ar', 'enteredInError')).getAttribute('dir')).toBeNull();
  });

  it('switches from cards to a list on resize without losing revisions', () => {
    const media = installMatchMedia(bandQueries('compact'));
    renderHistory();
    expect(historyRoot().getAttribute('data-revision-view')).toBe('cards');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(historyRoot().getAttribute('data-breakpoint')).toBe('medium');
    expect(historyRoot().getAttribute('data-revision-view')).toBe('list');
    expect(historyRoot().classList.contains('card-list')).toBe(false);
    expect(screen.getAllByRole('listitem')).toHaveLength(historyRevisions.length);
  });

  it('keeps the stable revision identity used as the list key at every breakpoint', () => {
    installMatchMedia(bandQueries('expanded'));
    renderHistory();
    const items = screen.getAllByRole('listitem');
    expect(items[0]?.getAttribute('data-revision-id')).toBe(signedWithError.id);
    expect(items.map((item) => item.getAttribute('data-revision-id'))).toEqual(
      historyRevisions.map((revision) => revision.id),
    );
  });
});
