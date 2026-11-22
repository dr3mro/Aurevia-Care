import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SignedOutPage } from '../../../src/features/system/SignedOutPage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type Layout = 'compact' | 'medium' | 'expanded';

/** Synthetic media answers for the three layout bands (no viewport is measured). */
interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
  portrait: boolean;
}

/** The same width and pointer states used by the responsive Playwright matrix. */
const VIEWPORTS: Record<Layout, ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true, portrait: true },
  medium: { medium: true, expanded: false, coarse: true, portrait: false },
  expanded: { medium: true, expanded: true, coarse: false, portrait: false },
};

/** Physical properties are never set by a responsive component (design §6, §11). */
const PHYSICAL_STYLE = /(?:padding|margin)-(?:left|right|top|bottom)\s*:|(?:^|;)\s*(?:left|right|top|bottom)\s*:/;

/**
 * Install a deterministic matchMedia stand-in keyed by media query string, so
 * the real breakpoint hooks resolve at each synthetic width (jsdom has none).
 */
function installMatchMedia(state: ViewportState): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: state.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: state.expanded,
    '(pointer: coarse)': state.coarse,
    '(orientation: portrait)': state.portrait,
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: answers[query] ?? false,
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

/** Render the signed-out page with the providers its notice and button need. */
function renderPage(locale: Locale): void {
  render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <SignedOutPage />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** Locate the responsive page wrapper that owns the notice (structure, not pixels). */
function pageWrapper(): HTMLElement {
  const page = document.querySelector<HTMLElement>('.page');
  if (!page) {
    throw new Error('signed-out page has no responsive page wrapper');
  }
  return page;
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive SignedOutPage at %s width (WEB-07, UX-01)',
  (layout) => {
    it('exposes the resolved breakpoint on a single logical page wrapper', () => {
      installMatchMedia(VIEWPORTS[layout]);
      renderPage('en');

      const page = pageWrapper();
      expect(page.getAttribute('data-breakpoint')).toBe(layout);
      expect(page.classList.contains('page')).toBe(true);
      expect(page.hidden).toBe(false);
      expect(page.style.display).toBe('grid');
      expect(page.style.placeItems).toBe('center');
      expect(page.style.minInlineSize).toBe('0');
      expect(page.style.overflowWrap).toBe('anywhere');
      expect(page.style.whiteSpace).toBe('normal');
      expect(page.style.textAlign).toBe('start');
      expect(page.getAttribute('style')).not.toMatch(PHYSICAL_STYLE);
      expect(page.style.display).not.toBe('none');
    });

    it('keeps the English heading, detail and single primary action with roles', () => {
      installMatchMedia(VIEWPORTS[layout]);
      renderPage('en');

      expect(document.documentElement.dir).toBe('ltr');
      expect(document.documentElement.lang).toBe('en');
      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
        'You have signed out',
      );
      expect(
        screen.getByText('Your session has ended. Sign in again to continue.'),
      ).toBeTruthy();
      const buttons = screen.getAllByRole('button');
      expect(buttons).toHaveLength(1);
      expect(buttons[0]?.textContent).toBe('Sign in');
      // SystemNotice keeps its labelled region; the wrapper adds no landmark.
      expect(screen.getByRole('region', { name: 'You have signed out' })).toBeTruthy();
    });

    it('stacks the notice in DOM order with a full-width logical touch target', () => {
      installMatchMedia(VIEWPORTS[layout]);
      renderPage('en');

      const stack = screen.getByRole('heading', { level: 1 }).closest('.stack');
      expect(stack).not.toBeNull();
      expect(stack?.parentElement).toBe(pageWrapper());
      expect(stack?.getAttribute('style')).not.toMatch(PHYSICAL_STYLE);
      expect(stack?.getAttribute('style')).not.toContain('display: none');

      const button = screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement;
      expect(button.tagName).toBe('BUTTON');
      expect(button.classList.contains('touch')).toBe(true);
      expect(button.getAttribute('data-breakpoint')).toBe(layout);
      expect(button.hasAttribute('hidden')).toBe(false);
      expect(button.style.display).not.toBe('none');
      expect(button.style.textAlign).toBe('start');
      expect(button.style.overflowWrap).toBe('anywhere');
      expect(button.getAttribute('style')).not.toMatch(PHYSICAL_STYLE);
    });

    it('keeps the single action in keyboard order and navigates home', () => {
      installMatchMedia(VIEWPORTS[layout]);
      window.history.replaceState(null, '', '/signed-out');
      renderPage('en');

      const button = screen.getByRole('button', { name: 'Sign in' });
      expect(button.tabIndex).toBe(0);
      button.focus();
      expect(document.activeElement).toBe(button);
      fireEvent.click(button);
      expect(window.location.pathname).toBe('/');
    });
  },
);

describe('responsive SignedOutPage under right-to-left', () => {
  it('mirrors the Arabic title, detail and action with logical properties only', () => {
    installMatchMedia(VIEWPORTS.compact);
    renderPage('ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('لقد سجّلت الخروج');
    expect(screen.getByText('انتهت جلستك. سجّل الدخول مرة أخرى للمتابعة.')).toBeTruthy();

    const button = screen.getByRole('button', { name: 'تسجيل الدخول' });
    expect(button.getAttribute('data-breakpoint')).toBe('compact');
    expect(button.classList.contains('touch')).toBe(true);
    expect(button.getAttribute('style')).not.toMatch(PHYSICAL_STYLE);
    expect(button.style.textAlign).toBe('start');

    const page = pageWrapper();
    expect(page.getAttribute('data-breakpoint')).toBe('compact');
    expect(page.getAttribute('style')).not.toMatch(PHYSICAL_STYLE);
    expect(page.style.textAlign).toBe('start');
    expect(page.style.overflowWrap).toBe('anywhere');
  });
});
