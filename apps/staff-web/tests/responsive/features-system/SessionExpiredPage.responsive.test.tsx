import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SessionExpiredPage } from '../../../src/features/system/SessionExpiredPage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES, breakpointFor } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type Layout = 'compact' | 'medium' | 'expanded';

/** Synthetic media states for the three layout bands (no real viewport is measured). */
const layouts: Record<Layout, Record<string, boolean>> = {
  compact: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: false,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
    '(pointer: coarse)': true,
  },
  medium: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: true,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
    '(pointer: coarse)': false,
  },
  expanded: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: true,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: true,
    '(pointer: coarse)': false,
  },
};

/** Every supported viewport width and the band its min-width queries select. */
const widths: readonly (readonly [number, Layout])[] = [
  [320, 'compact'],
  [360, 'compact'],
  [390, 'compact'],
  [768, 'medium'],
  [820, 'medium'],
  [1024, 'medium'],
  [1280, 'expanded'],
];

/** A deterministic matchMedia stand-in keyed by media query string. */
function installMatchMedia(initial: Record<string, boolean>): void {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const matches = initial[query] ?? false;
      return {
        matches,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
      };
    }),
  );
}

/** Render the page under the providers it needs, in a chosen locale and layout. */
function renderPage(locale: Locale, layout: Layout) {
  installMatchMedia(layouts[layout]);
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <SessionExpiredPage />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The alert panel the page renders through the shared session-expired state. */
function panel(): HTMLElement {
  const region = screen.queryByRole('alert');
  if (!region) {
    throw new Error('session-expired page did not render an alert panel');
  }
  return region;
}

/** The action row wrapping the single sign-in control. */
function actionRow(): HTMLElement {
  const button = screen.getByRole('button');
  const wrapper = button.parentElement;
  if (!wrapper?.parentElement) {
    throw new Error('session-expired page has no action row');
  }
  return wrapper.parentElement;
}

/** Assert the wrapping, logical-property style the state panel promises. */
function expectWrappedLogicalSurface(region: HTMLElement): void {
  const style = region.getAttribute('style') ?? '';
  expect(style).toContain('place-items: center');
  expect(style).toContain('text-align: start');
  expect(style).toContain('overflow-wrap: anywhere');
  expect(style).toContain('max-inline-size: 44rem');
  expect(style).not.toMatch(/padding-left|padding-right|margin-left|margin-right/);
  expect(style).not.toMatch(/text-align: (left|right)/);
  expect(style).not.toContain('direction: rtl');
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('responsive SessionExpiredPage (WEB-07, UX-01)', () => {
  it.each(widths)('renders the full alert at %i px (%s band)', (width, expected) => {
    expect(breakpointFor(width)).toBe(expected);
    const { unmount } = renderPage('en', expected);

    const region = panel();
    expect(region.getAttribute('role')).toBe('alert');
    expect(region.getAttribute('aria-live')).toBe('assertive');
    expect(region.getAttribute('data-breakpoint')).toBe(expected);
    expect(region.className).toContain('page');
    expectWrappedLogicalSurface(region);

    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('Your session has expired');
    expect(region.textContent).toContain('Sign in again to continue where you left off.');
    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0]?.textContent).toBe('Sign in');
    expect(buttons[0]?.parentElement?.className).toContain('touch');

    // A phone stacks the one action; the wider bands keep it on one inline line.
    expect(actionRow().className).toContain(expected === 'compact' ? 'stack' : 'cluster');

    // No clinical data, table or input is hidden or replaced at any width.
    expect(region.getAttribute('style')).not.toContain('display: none');
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByRole('navigation')).toBeNull();

    // The sign-in action is the only focusable control and keeps DOM order.
    buttons[0]?.focus();
    expect(document.activeElement).toBe(buttons[0]);
    unmount();
  });

  it.each(['compact', 'medium', 'expanded'] as const)(
    'keeps the sign-in action wired to the home route in the %s band',
    (layout) => {
      window.history.replaceState(null, '', '/session-expired');
      renderPage('en', layout);
      fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
      expect(window.location.pathname).toBe('/');
    },
  );

  it.each(['compact', 'medium', 'expanded'] as const)(
    'mirrors the panel and wraps Arabic text in the %s band',
    (layout) => {
      const { unmount } = renderPage('ar', layout);
      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');
      expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('انتهت جلستك');
      expect(panel().textContent).toContain('سجّل الدخول مرة أخرى للمتابعة من حيث توقفت.');
      expect(screen.getByRole('button', { name: 'تسجيل الدخول' })).toBeTruthy();
      expectWrappedLogicalSurface(panel());
      expect(actionRow().className).toContain(layout === 'compact' ? 'stack' : 'cluster');
      unmount();
    },
  );

  it('keeps text reflowable without clipped or hidden content at 200 percent zoom', () => {
    // jsdom cannot paint pixels or evaluate zoom; the stylesheet and Playwright
    // projects own the painted checks. Here we lock the DOM contract they rely on.
    for (const width of [320, 390, 768, 1280] as const) {
      const { unmount } = renderPage('en', breakpointFor(width));
      const style = panel().getAttribute('style') ?? '';
      expect(style).toContain('overflow-wrap: anywhere');
      expect(style).not.toContain('white-space: nowrap');
      expect(style).not.toContain('text-overflow');
      expect(style).not.toContain('display: none');
      expect(screen.getByRole('heading', { level: 2 }).textContent?.length).toBeGreaterThan(0);
      unmount();
    }
  });
});
