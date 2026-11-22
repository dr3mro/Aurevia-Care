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
}

/** The width states used by the responsive Playwright matrix. */
const VIEWPORTS: Record<Layout, ViewportState> = {
  compact: { medium: false, expanded: false },
  medium: { medium: true, expanded: false },
  expanded: { medium: true, expanded: true },
};

/**
 * Install a deterministic matchMedia stand-in keyed by media query string, so
 * the real breakpoint hooks resolve at each synthetic width (jsdom has none).
 * `SignedOutPage` reads only the width queries; the coarse-pointer 44px floor is
 * owned by `.touch` in responsive.css and asserted by responsiveStylesheet.test.
 */
function installMatchMedia(state: ViewportState): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: state.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: state.expanded,
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
    it('delegates layout to the page class with the resolved breakpoint only', () => {
      installMatchMedia(VIEWPORTS[layout]);
      renderPage('en');

      const page = pageWrapper();
      expect(page.getAttribute('data-breakpoint')).toBe(layout);
      expect(page.classList.contains('page')).toBe(true);
      expect(page.hidden).toBe(false);
      // No inline declarations: they would outrank a future stylesheet rule.
      expect(page.hasAttribute('style')).toBe(false);
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

    it('stacks the notice in DOM order inside a class-only wrapper', () => {
      installMatchMedia(VIEWPORTS[layout]);
      renderPage('en');

      const stack = pageWrapper().querySelector(':scope > .stack');
      expect(stack).not.toBeNull();
      expect(stack?.parentElement).toBe(pageWrapper());
      expect(stack?.hasAttribute('style')).toBe(false);

      // Button owns its `touch` class and breakpoint; the stylesheet owns the
      // 44px coarse-pointer floor, so no inline size may be introduced here.
      const button = screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement;
      expect(button.tagName).toBe('BUTTON');
      expect(button.classList.contains('touch')).toBe(true);
      expect(button.getAttribute('data-breakpoint')).toBe(layout);
      expect(button.hasAttribute('hidden')).toBe(false);
      expect(button.style.display).not.toBe('none');
      expect(button.style.textAlign).toBe('start');
      expect(button.style.overflowWrap).toBe('anywhere');
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

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive SignedOutPage under right-to-left at %s width (WEB-07, UX-01)',
  (layout) => {
    it('mirrors the Arabic title, detail and action with logical layout only', () => {
      installMatchMedia(VIEWPORTS[layout]);
      renderPage('ar');

      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');
      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('لقد سجّلت الخروج');
      expect(screen.getByText('انتهت جلستك. سجّل الدخول مرة أخرى للمتابعة.')).toBeTruthy();

      const button = screen.getByRole('button', { name: 'تسجيل الدخول' });
      expect(button.getAttribute('data-breakpoint')).toBe(layout);
      expect(button.classList.contains('touch')).toBe(true);
      expect(button.style.textAlign).toBe('start');

      const page = pageWrapper();
      expect(page.getAttribute('data-breakpoint')).toBe(layout);
      expect(page.hasAttribute('style')).toBe(false);
    });
  },
);
