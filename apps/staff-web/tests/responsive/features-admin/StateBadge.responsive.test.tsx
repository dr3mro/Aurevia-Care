import { act, render, screen, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { StateBadge } from '../../../src/features/admin/StateBadge';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { breakpointFor, BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query string. One state
 * object is reused per query so the breakpoint hook's initializer and effect
 * observe the same listeners. Every value below is synthetic clinical text.
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
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

const layouts = {
  compact: { medium: false, expanded: false },
  medium: { medium: true, expanded: false },
  expanded: { medium: true, expanded: true },
} as const;

type Layout = keyof typeof layouts;

/** Media-query answers for one synthetic layout band. */
function queriesFor(layout: Layout): Record<string, boolean> {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: layouts[layout].medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: layouts[layout].expanded,
  };
}

const LONG_ENGLISH =
  'A deliberately long synthetic verification status that must wrap at 320 px and at 200 percent zoom without clipping or overlap';
const LONG_ARABIC = 'حالة تحقق سريرية تجريبية طويلة جداً يجب أن تلتف دون قص أو تداخل عند العرض الضيق';

/** Render a badge under the real i18n and theme providers for a locale. */
function renderBadge(locale: Locale, ui: ReactNode) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{ui}</I18nProvider>
    </ThemeProvider>,
  );
}

/** The single badge rendered by every case in this file. */
function badge(): HTMLElement {
  return screen.getByTestId('badge-responsive');
}

afterEach(() => {
  vi.unstubAllGlobals();
  // Keep this file's cases independent: the RTL case must not leak its
  // document direction or language into a later case.
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive StateBadge at %s width (WEB-07, UX-01)',
  (layout) => {
    it('keeps a wrapping, unhidden word and shape-distinct glyph with the active band', () => {
      installMatchMedia(queriesFor(layout));
      const { unmount } = renderBadge(
        'en',
        <StateBadge glyph="✓" label={LONG_ENGLISH} tone="verified" testId="badge-responsive" />,
      );

      const root = badge();
      expect(root.tagName).toBe('SPAN');
      expect(root.getAttribute('data-state')).toBe('verified');
      expect(root.getAttribute('data-breakpoint')).toBe(layout);
      // The stylesheet's cluster rule owns flex-wrap, the logical gap and the
      // child min-size wrapping rule; the badge adds no new CSS of its own.
      expect(root.classList.contains('cluster')).toBe(true);

      // The glyph is decorative but persistent: colour is not the only cue,
      // and it is never hidden at a narrower band.
      const glyph = root.querySelector('[aria-hidden="true"]');
      expect(glyph).toBeTruthy();
      expect(glyph?.textContent).toBe('✓');
      expect(glyph?.getAttribute('aria-hidden')).toBe('true');

      // The state word stays in the accessibility tree as an isolated run.
      const label = within(root).getByText(LONG_ENGLISH) as HTMLElement;
      expect(label.tagName).toBe('BDI');
      expect(label.getAttribute('dir')).toBe('auto');
      expect(label.getAttribute('lang')).toBe('en');
      expect(label.style.whiteSpace).toBe('normal');
      expect(label.style.overflowWrap).toBe('anywhere');

      // No fixed block size, nowrap or hidden clinical value: the label wraps
      // instead of being clipped, and text stays readable at 200 percent zoom.
      const style = root.getAttribute('style') ?? '';
      expect(style).not.toContain('display: none');
      expect(style).not.toContain('white-space: nowrap');
      expect(style).not.toMatch(/height:\s*\d+px/);
      expect(root.hasAttribute('hidden')).toBe(false);
      unmount();
    });

    it('uses logical properties only and introduces no unexpected role', () => {
      installMatchMedia(queriesFor(layout));
      renderBadge(
        'en',
        <StateBadge glyph="○" label="Synthetic pending" tone="pending" testId="badge-responsive" />,
      );

      const root = badge();
      // A badge is a plain structural span: no live-region or control role is
      // invented, and the glyph alone is removed from the accessibility tree.
      expect(root.getAttribute('role')).toBeNull();
      expect(root.querySelector('[aria-hidden="true"]')?.textContent).toBe('○');
      for (const element of [root, within(root).getByText('Synthetic pending') as HTMLElement]) {
        const inline = element.getAttribute('style') ?? '';
        expect(inline).not.toMatch(/(?:margin|padding|border|inset)-(?:left|right)\s*:/);
        expect(inline).not.toMatch(/text-align\s*:\s*(?:left|right)/);
      }
    });
  },
);

it('maps every supported width to the matching layout band', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;
  for (const width of widths) {
    const expected = breakpointFor(width);
    installMatchMedia(queriesFor(expected));
    const { unmount } = renderBadge(
      'en',
      <StateBadge glyph="+" label="Synthetic width" tone="effective" testId="badge-responsive" />,
    );
    // jsdom cannot paint pixels; the breakpoint hook resolves the same band a
    // real viewport would, and pixel geometry is left to the Playwright tasks.
    expect(badge().getAttribute('data-breakpoint')).toBe(expected);
    unmount();
  }
});

it('adds no tab stop and preserves the keyboard order of surrounding controls', () => {
  installMatchMedia(queriesFor('compact'));
  const { container } = renderBadge(
    'en',
    <>
      <button type="button">Before</button>
      <StateBadge glyph="✓" label="Synthetic focus" tone="verified" testId="badge-responsive" />
      <button type="button">After</button>
    </>,
  );

  const buttons = screen.getAllByRole('button');
  expect(buttons.map((button) => button.textContent)).toEqual(['Before', 'After']);
  for (const button of buttons) {
    button.focus();
    expect(document.activeElement).toBe(button);
  }
  // The badge introduces no focusable element and never reorders the controls.
  expect(container.querySelectorAll('a[href], button, input, select, textarea, [tabindex]')).toHaveLength(
    2,
  );
  expect(badge().tabIndex).toBe(-1);
});

it('mirrors an Arabic RTL badge without hiding or overriding the clinical label', () => {
  document.documentElement.setAttribute('dir', 'rtl');
  document.documentElement.setAttribute('lang', 'ar');
  installMatchMedia(queriesFor('compact'));
  renderBadge(
    'ar',
    <StateBadge glyph="○" label={LONG_ARABIC} tone="pending" testId="badge-responsive" />,
  );

  expect(document.documentElement.dir).toBe('rtl');
  expect(document.documentElement.lang).toBe('ar');
  const root = badge();
  expect(root.getAttribute('data-breakpoint')).toBe('compact');
  const label = within(root).getByText(LONG_ARABIC) as HTMLElement;
  expect(label.tagName).toBe('BDI');
  expect(label.getAttribute('dir')).toBe('auto');
  expect(label.getAttribute('lang')).toBe('ar');
  expect(label.textContent).toBe(LONG_ARABIC);
  // Direction comes from the document; no subtree override, physical side or
  // hidden value is introduced by the badge itself.
  expect(root.style.direction).toBe('');
  expect(label.style.direction).toBe('');
  expect(label.style.whiteSpace).toBe('normal');
  expect(label.style.overflowWrap).toBe('anywhere');
  const style = root.getAttribute('style') ?? '';
  expect(style).not.toMatch(/(?:margin|padding|border|inset)-(?:left|right)\s*:/);
  expect(style).not.toContain('display: none');
});

it('follows a live viewport change without remounting or hiding the state value', () => {
  const media = installMatchMedia(queriesFor('compact'));
  renderBadge(
    'en',
    <StateBadge glyph="✓" label={LONG_ENGLISH} tone="verified" testId="badge-responsive" />,
  );

  const root = badge();
  expect(root.getAttribute('data-breakpoint')).toBe('compact');

  act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
  expect(root.getAttribute('data-breakpoint')).toBe('medium');
  expect(within(root).getByText(LONG_ENGLISH)).toBeTruthy();

  act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
  expect(root.getAttribute('data-breakpoint')).toBe('expanded');
  expect(within(root).getByText(LONG_ENGLISH)).toBeTruthy();
});
