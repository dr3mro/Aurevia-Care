import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { VisitStateBadge } from '../../../src/features/scheduling/VisitStateBadge';
import type { VisitState } from '../../../src/features/scheduling/domain';
import { schedulingText, visitStateMessageKey } from '../../../src/features/scheduling/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query string. One state
 * object is reused per query so the hook's initializer and effect see the same
 * listeners, and `fire` can drive a viewport change without a remount.
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
    vi.fn((query: string) => ({
      get matches() {
        return state(query).matches;
      },
      media: query,
      onchange: null,
      addEventListener: (_type: string, listener: ChangeListener) => {
        state(query).listeners.add(listener);
      },
      removeEventListener: (_type: string, listener: ChangeListener) => {
        state(query).listeners.delete(listener);
      },
    })),
  );
  return {
    fire(query: string, matches: boolean) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches, media: query } as MediaQueryListEvent);
      }
    },
  };
}

type Layout = 'compact' | 'medium' | 'expanded';

/** Synthetic viewport states mirroring the responsive Playwright matrix. */
const VIEWPORTS: Record<
  Layout,
  { medium: boolean; expanded: boolean; coarse: boolean; portrait: boolean }
> = {
  compact: { medium: false, expanded: false, coarse: true, portrait: true },
  medium: { medium: true, expanded: false, coarse: true, portrait: false },
  expanded: { medium: true, expanded: true, coarse: false, portrait: false },
};

function queriesFor(layout: Layout): Record<string, boolean> {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: VIEWPORTS[layout].medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: VIEWPORTS[layout].expanded,
    '(pointer: coarse)': VIEWPORTS[layout].coarse,
    '(orientation: portrait)': VIEWPORTS[layout].portrait,
  };
}

const ALL_STATES: readonly VisitState[] = [
  'booked',
  'checked-in',
  'in-progress',
  'completed',
  'cancelled',
  'no-show',
];

/** Physical inline/block properties must never appear in a component style. */
const physicalStyleKeys = [
  'marginLeft',
  'marginRight',
  'marginTop',
  'marginBottom',
  'paddingLeft',
  'paddingRight',
  'paddingTop',
  'paddingBottom',
  'borderLeft',
  'borderRight',
  'borderTop',
  'borderBottom',
  'left',
  'right',
  'top',
  'bottom',
];

function renderBadge(state: VisitState, locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <VisitStateBadge state={state} locale={locale} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The badge root exposes its state and active band for layout assertions. */
function badgeRoot(): HTMLElement {
  const root = document.querySelector('[data-state]');
  if (!root) {
    throw new Error('visit-state badge root was not rendered');
  }
  return root as HTMLElement;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive VisitStateBadge at %s width',
  (layout) => {
    it('keeps one DOM structure and exposes the active band at this width', () => {
      installMatchMedia(queriesFor(layout));
      renderBadge('booked', 'en');

      const root = badgeRoot();
      expect(root.getAttribute('data-state')).toBe('booked');
      expect(root.getAttribute('data-breakpoint')).toBe(layout);
      const icon = root.querySelector('[data-badge-icon]') as HTMLElement | null;
      expect(icon).not.toBeNull();
      expect(icon?.getAttribute('aria-hidden')).toBe('true');
      expect(icon?.textContent).toBe('○');

      const text = screen.getByText(schedulingText('en', 'stateBooked')) as HTMLElement;
      expect(text.tagName).toBe('BDI');
      expect(text.getAttribute('data-breakpoint')).toBe(layout);
      expect(text.getAttribute('dir')).toBe('auto');
      expect(text.getAttribute('lang')).toBe('en');
    });

    it('shows a distinct glyph and translated label for every state with no live region', () => {
      installMatchMedia(queriesFor(layout));
      const glyphs: (string | null)[] = [];
      for (const state of ALL_STATES) {
        const { unmount } = renderBadge(state, 'en');
        const label = schedulingText('en', visitStateMessageKey[state]);
        const text = screen.getByText(label) as HTMLElement;
        const root = text.closest('[data-state]') as HTMLElement | null;
        expect(root?.getAttribute('data-state')).toBe(state);
        const glyph = root?.querySelector('[data-badge-icon]')?.textContent ?? null;
        expect(glyph).toBeTruthy();
        glyphs.push(glyph);
        expect(text.getAttribute('dir')).toBe('auto');
        expect(root?.getAttribute('aria-live')).toBeNull();
        unmount();
      }
      // Six states keep six distinct shapes, so status never relies on colour.
      expect(new Set(glyphs).size).toBe(ALL_STATES.length);
    });

    it('wraps with logical inline styles and hides no clinical text', () => {
      installMatchMedia(queriesFor(layout));
      renderBadge('checked-in', 'en');

      const root = badgeRoot();
      const text = screen.getByText(schedulingText('en', 'stateCheckedIn')) as HTMLElement;
      const icon = root.querySelector('[data-badge-icon]') as HTMLElement;
      expect(root.style.display).toBe('inline-flex');
      expect(root.style.flexWrap).toBe('wrap');
      expect(root.style.minInlineSize === '0' || root.style.minInlineSize === '0px').toBe(true);
      expect(root.style.maxInlineSize).toBe('100%');
      // The wrapped run keeps normal spacing and can break anywhere, so long
      // English/Arabic labels neither clip nor force a page-level scroll.
      expect(text.style.whiteSpace).toBe('normal');
      expect(text.style.overflowWrap).toBe('anywhere');
      expect(text.style.minInlineSize === '0' || text.style.minInlineSize === '0px').toBe(true);

      // No fixed block size or font size is set, so the badge grows with text
      // at 200 percent zoom. No state word or glyph is hidden at any width.
      const style = root.getAttribute('style') ?? '';
      expect(style).not.toMatch(/height:\s*\d+px/);
      expect(style).not.toMatch(/font-size:/);
      for (const element of [root, icon, text]) {
        expect(element.style.display).not.toBe('none');
        expect(element.hasAttribute('hidden')).toBe(false);
      }
      expect(root.querySelector('.hide-compact')).toBeNull();
      expect(root.querySelector('.only-compact')).toBeNull();
    });

    it('uses logical properties only, with no physical side in any inline style', () => {
      installMatchMedia(queriesFor(layout));
      renderBadge('completed', 'en');

      const root = badgeRoot();
      const styled = Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root);
      for (const element of styled) {
        expect(element.style.direction).toBe('');
        for (const key of physicalStyleKeys) {
          expect(element.style.getPropertyValue(key)).toBe('');
        }
      }
    });

    it('adds no tab stop and keeps DOM order of icon then label', () => {
      installMatchMedia(queriesFor(layout));
      const { container } = renderBadge('booked', 'en');

      const root = badgeRoot();
      const icon = root.querySelector('[data-badge-icon]');
      const text = screen.getByText(schedulingText('en', 'stateBooked'));
      expect(Array.from(root.children)).toEqual([icon, text]);
      expect(root.tabIndex).toBe(-1);
      // A status badge is presentational: it introduces no keyboard stop, so
      // the surrounding worklist keeps its existing keyboard order.
      expect(container.querySelectorAll('a[href], button, input, select, textarea, [tabindex]')).toHaveLength(0);
    });
  },
);

describe('responsive VisitStateBadge viewport matrix', () => {
  it.each([320, 360, 390, 768, 820, 1024, 1280] as const)(
    'resolves %ipx to the correct layout band',
    (width) => {
      installMatchMedia({
        [BREAKPOINT_MEDIA_QUERIES.medium]: width >= BREAKPOINT_WIDTHS.medium,
        [BREAKPOINT_MEDIA_QUERIES.expanded]: width >= BREAKPOINT_WIDTHS.expanded,
        '(pointer: coarse)': width < BREAKPOINT_WIDTHS.expanded,
        '(orientation: portrait)': width < BREAKPOINT_WIDTHS.medium,
      });
      renderBadge('booked', 'en');

      const expected =
        width >= BREAKPOINT_WIDTHS.expanded
          ? 'expanded'
          : width >= BREAKPOINT_WIDTHS.medium
            ? 'medium'
            : 'compact';
      expect(badgeRoot().getAttribute('data-breakpoint')).toBe(expected);
      expect(
        (screen.getByText(schedulingText('en', 'stateBooked')) as HTMLElement).getAttribute(
          'data-breakpoint',
        ),
      ).toBe(expected);
    },
  );
});

describe('responsive VisitStateBadge band changes and right-to-left', () => {
  it('follows a viewport change without remounting or altering the label', () => {
    const media = installMatchMedia(queriesFor('compact'));
    renderBadge('no-show', 'en');

    const root = badgeRoot();
    const label = schedulingText('en', 'stateNoShow');
    expect(root.getAttribute('data-breakpoint')).toBe('compact');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(root.getAttribute('data-breakpoint')).toBe('medium');
    expect(root.getAttribute('data-state')).toBe('no-show');
    expect(screen.getByText(label).textContent).toBe(label);

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(root.getAttribute('data-breakpoint')).toBe('expanded');
    expect(screen.getByText(label).textContent).toBe(label);
  });

  it('mirrors under RTL with a long Arabic label and no local direction override', () => {
    installMatchMedia(queriesFor('compact'));
    const state: VisitState = 'checked-in';
    renderBadge(state, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const root = badgeRoot();
    expect(root.getAttribute('data-state')).toBe(state);
    expect(root.getAttribute('data-breakpoint')).toBe('compact');

    const label = schedulingText('ar', visitStateMessageKey[state]);
    const text = screen.getByText(label) as HTMLElement;
    expect(text.getAttribute('dir')).toBe('auto');
    expect(text.getAttribute('lang')).toBe('ar');
    expect(text.textContent).toBe(label);
    expect(text.style.direction).toBe('');
    expect(text.style.whiteSpace).toBe('normal');
    expect(text.style.overflowWrap).toBe('anywhere');
    const style = root.getAttribute('style') ?? '';
    expect(style).not.toMatch(/(?:margin|padding|border|inset)-(?:left|right)\s*:/);
    expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
  });

  it('introduces no interactive target, so no 44px touch control is required', () => {
    installMatchMedia(queriesFor('compact'));
    const { container } = renderBadge('booked', 'en');

    const root = badgeRoot();
    expect(root.classList.contains('touch')).toBe(false);
    expect(root.style.minBlockSize).toBe('');
    expect(container.querySelectorAll('button, a[href], input, select, textarea')).toHaveLength(0);
  });
});
