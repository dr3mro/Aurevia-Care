import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ForbiddenPage } from '../../../src/features/system/ForbiddenPage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type Layout = 'compact' | 'medium' | 'expanded';

interface Viewport {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
}

/** Synthetic media answers for the three layout bands (no pixels are measured). */
const VIEWPORTS: Record<Layout, Viewport> = {
  compact: { medium: false, expanded: false, coarse: true },
  medium: { medium: true, expanded: false, coarse: true },
  expanded: { medium: true, expanded: true, coarse: false },
};

/** Install a deterministic matchMedia stand-in keyed by media query string. */
function installMatchMedia(viewport: Viewport): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: viewport.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: viewport.expanded,
    '(pointer: coarse)': viewport.coarse,
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
      dispatchEvent: () => true,
    })),
  );
}

function renderPage(locale: Locale, layout: Layout) {
  installMatchMedia(VIEWPORTS[layout]);
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ForbiddenPage />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The alert panel owned by the shared forbidden state view. */
function alertPanel(): HTMLElement {
  return screen.getByRole('alert');
}

/** The single action, its coarse-pointer wrapper, its layout row and page container. */
function actionParts(): {
  button: HTMLButtonElement;
  touch: HTMLElement;
  row: HTMLElement;
  page: HTMLElement;
} {
  const buttons = screen.getAllByRole('button');
  expect(buttons).toHaveLength(1);
  const button = buttons[0];
  if (!(button instanceof HTMLButtonElement)) {
    throw new Error('ForbiddenPage must render one native button');
  }
  const touch = button.parentElement;
  const row = touch?.parentElement;
  const page = row?.parentElement;
  if (!touch || !row || !page) {
    throw new Error('ForbiddenPage action is missing its wrapper, row or page container');
  }
  return { button, touch, row, page };
}

/** Physical side declarations are layout defects under right-to-left. */
const PHYSICAL_SIDES =
  /(?:padding|margin)-(?:left|right|top|bottom)\s*:|(?:^|;)\s*(?:left|right|top|bottom)\s*:|text-align\s*:\s*(?:left|right)/;

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('responsive ForbiddenPage (WEB-07, UX-01)', () => {
  it.each(['compact', 'medium', 'expanded'] as const)(
    'keeps the explanation and one labelled action at %s width',
    (layout) => {
      const { unmount } = renderPage('en', layout);
      expect(document.documentElement.dir).toBe('ltr');
      expect(document.documentElement.lang).toBe('en');

      const panel = alertPanel();
      expect(panel.getAttribute('data-breakpoint')).toBe(layout);
      expect(panel.className).toContain('page');
      expect(
        screen.getByRole('heading', { level: 2, name: 'You do not have access' }),
      ).toBeTruthy();
      expect(screen.getByText('Your role cannot perform this action.')).toBeTruthy();
      expect(panel.textContent?.length ?? 0).toBeGreaterThan(0);

      const { button } = actionParts();
      expect(button.textContent).toBe('Go home');
      expect(button.getAttribute('data-breakpoint')).toBe(layout);
      expect(button.style.overflowWrap).toBe('anywhere');
      expect(button.style.textAlign).toBe('start');
      unmount();
    },
  );

  it('stacks the action on phones and keeps it inline from the medium band up', () => {
    for (const layout of ['compact', 'medium', 'expanded'] as const) {
      const { unmount } = renderPage('en', layout);
      const { button, touch, row, page } = actionParts();
      expect(page.getAttribute('data-breakpoint')).toBe(layout);
      expect(page.getAttribute('style')).toContain('max-inline-size: 44rem');
      expect(row.getAttribute('data-breakpoint')).toBe(layout);
      if (layout === 'compact') {
        expect(row.className).toContain('stack');
        expect(row.className).not.toContain('cluster');
      } else {
        expect(row.className).toContain('cluster');
        expect(row.className).not.toContain('stack');
      }
      expect(touch.className).toContain('touch');
      expect(touch.style.inlineSize).toBe('');
      expect(button.className).toContain('touch');
      expect(button.style.minBlockSize).toBe('');
      expect(button.style.minInlineSize).toBe('');
      unmount();
    }
  });

  it('keeps the single action in keyboard order at every width', () => {
    for (const layout of ['compact', 'medium', 'expanded'] as const) {
      const { unmount } = renderPage('en', layout);
      const { button } = actionParts();
      expect(button.tabIndex).toBe(0);
      expect(button.hasAttribute('hidden')).toBe(false);
      expect(button.style.display).toBe('');
      button.focus();
      expect(document.activeElement).toBe(button);
      unmount();
    }
  });

  it('wraps long labels and uses only logical properties at every width', () => {
    for (const layout of ['compact', 'medium', 'expanded'] as const) {
      const { unmount } = renderPage('ar', layout);
      const panel = alertPanel();
      const { button, row, page } = actionParts();
      for (const element of [panel, page, row, button]) {
        const style = element.getAttribute('style') ?? '';
        expect(style).not.toMatch(PHYSICAL_SIDES);
        expect(style).not.toContain('display: none');
      }
      expect(panel.getAttribute('style')).toContain('overflow-wrap: anywhere');
      expect(button.getAttribute('dir')).toBeNull();
      expect(button.style.direction).toBe('');
      unmount();
    }
  });

  it('mirrors the Arabic page right to left without hiding any text', () => {
    for (const layout of ['compact', 'expanded'] as const) {
      const { unmount } = renderPage('ar', layout);
      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');
      expect(
        screen.getByRole('heading', { level: 2, name: 'لا تملك صلاحية الوصول' }),
      ).toBeTruthy();
      expect(screen.getByText('لا يمكن لدورك تنفيذ هذا الإجراء.')).toBeTruthy();
      expect(screen.getByRole('button', { name: 'الصفحة الرئيسية' })).toBeTruthy();
      expect(screen.queryByText('You do not have access')).toBeNull();
      unmount();
    }
  });

  it('returns to the home page from the single action', () => {
    window.history.replaceState(null, '', '/forbidden');
    renderPage('en', 'compact');
    fireEvent.click(screen.getByRole('button', { name: 'Go home' }));
    expect(window.location.pathname).toBe('/');
  });
});
