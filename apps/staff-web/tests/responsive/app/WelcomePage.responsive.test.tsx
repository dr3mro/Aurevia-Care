import { render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { WelcomePage } from '../../../src/app/WelcomePage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

const BREAKPOINTS: readonly Breakpoint[] = ['compact', 'medium', 'expanded'];

/** Physical style properties a direction-safe wrapper must never set inline. */
const PHYSICAL_STYLE_KEYS = [
  'marginLeft',
  'marginRight',
  'marginTop',
  'marginBottom',
  'paddingLeft',
  'paddingRight',
  'paddingTop',
  'paddingBottom',
  'left',
  'right',
  'top',
  'bottom',
] as const;

interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
  portrait: boolean;
}

/** Synthetic media states mirroring the responsive Playwright matrix. */
const VIEWPORTS: Record<Breakpoint, ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true, portrait: true },
  medium: { medium: true, expanded: false, coarse: true, portrait: false },
  expanded: { medium: true, expanded: true, coarse: false, portrait: false },
};

/**
 * jsdom cannot evaluate media queries. Install a deterministic stand-in so the
 * real breakpoint hook resolves at each synthetic width (WEB-07, UX-01).
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
    })),
  );
}

/** Render the welcome route through its real providers at one synthetic band. */
function renderWelcome(breakpoint: Breakpoint, locale: Locale = 'en') {
  installMatchMedia(VIEWPORTS[breakpoint]);
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <WelcomePage />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The labelled outer section owned by WelcomePage, not the EmptyState panel. */
function welcomeRoot(): HTMLElement {
  const root = document.querySelector<HTMLElement>('section[aria-labelledby="welcome-title"]');
  if (!root) {
    throw new Error('WelcomePage did not render its labelled section');
  }
  return root;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(BREAKPOINTS)('responsive WelcomePage at %s', (breakpoint) => {
  it('renders the labelled page wrapper with the welcome heading and empty-state status', () => {
    renderWelcome(breakpoint);
    const root = welcomeRoot();

    expect(root.getAttribute('aria-labelledby')).toBe('welcome-title');
    expect(root.getAttribute('data-breakpoint')).toBe(breakpoint);
    expect(root.classList.contains('page')).toBe(true);
    expect(root.classList.contains('hide-compact')).toBe(false);
    expect(root.classList.contains('only-compact')).toBe(false);

    const heading = screen.getByRole('heading', { level: 2, name: 'Welcome' });
    expect(heading.id).toBe('welcome-title');
    expect(root.contains(heading)).toBe(true);

    const status = screen.getByRole('status');
    expect(root.contains(status)).toBe(true);
    expect(status.classList.contains('page')).toBe(true);
  });

  it('keeps logical inline styles and wraps long text without hiding content', () => {
    renderWelcome(breakpoint);
    const root = welcomeRoot();
    const style = root.getAttribute('style') ?? '';

    expect(style).toMatch(/min-inline-size:\s*0(?:px)?/);
    expect(style).toContain('overflow-wrap: anywhere');
    expect(style).toContain('text-align: start');
    for (const key of PHYSICAL_STYLE_KEYS) {
      expect(root.style.getPropertyValue(key)).toBe('');
    }
    expect(root.style.display).not.toBe('none');
    expect(root.style.whiteSpace).not.toBe('nowrap');

    const subtree = Array.from(root.querySelectorAll<HTMLElement>('*')).concat(root);
    for (const element of subtree) {
      expect(element.style.display).not.toBe('none');
    }
  });

  it('keeps the heading before the status region in DOM order with no forced tabindex', () => {
    renderWelcome(breakpoint);
    const root = welcomeRoot();
    const heading = within(root).getByRole('heading', { level: 2, name: 'Welcome' });
    const status = within(root).getByRole('status');

    expect(heading.compareDocumentPosition(status) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(root.querySelectorAll('button, input, select, textarea, a[href], [tabindex]')).toHaveLength(
      0,
    );
  });
});

describe('responsive WelcomePage viewport matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;
  // The acceptance matrix requires every concrete width in both writing directions.
  const locales: readonly Locale[] = ['en', 'ar'];

  it.each(widths.flatMap((width) => locales.map((locale) => ({ width, locale }))))(
    'resolves $width px to the correct layout band in $locale',
    ({ width, locale }) => {
      installMatchMedia({
        medium: width >= BREAKPOINT_WIDTHS.medium,
        expanded: width >= BREAKPOINT_WIDTHS.expanded,
        coarse: width < BREAKPOINT_WIDTHS.expanded,
        portrait: width < BREAKPOINT_WIDTHS.medium,
      });
      render(
        <ThemeProvider>
          <I18nProvider initialLocale={locale}>
            <WelcomePage />
          </I18nProvider>
        </ThemeProvider>,
      );

      const expected: Breakpoint =
        width >= BREAKPOINT_WIDTHS.expanded
          ? 'expanded'
          : width >= BREAKPOINT_WIDTHS.medium
            ? 'medium'
            : 'compact';
      expect(welcomeRoot().getAttribute('data-breakpoint')).toBe(expected);

      const arabic = locale === 'ar';
      expect(document.documentElement.dir).toBe(arabic ? 'rtl' : 'ltr');
      expect(document.documentElement.lang).toBe(locale);
      const headingName = arabic ? 'مرحباً' : 'Welcome';
      expect(
        within(welcomeRoot()).getByRole('heading', { level: 2, name: headingName }).textContent,
      ).toBe(headingName);
      expect(screen.getByRole('status')).toBeTruthy();
    },
  );
});

describe('responsive WelcomePage right-to-left', () => {
  it.each(BREAKPOINTS)(
    'mirrors the Arabic welcome heading with logical properties only at %s',
    (breakpoint) => {
      renderWelcome(breakpoint, 'ar');
      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');

      const root = welcomeRoot();
      expect(root.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(root.getAttribute('dir')).toBeNull();
      expect(root.style.direction).toBe('');

      const heading = within(root).getByRole('heading', { level: 2, name: 'مرحباً' });
      expect(heading.textContent).toBe('مرحباً');
      expect(heading.getAttribute('dir')).toBeNull();

      const style = root.getAttribute('style') ?? '';
      expect(style).toContain('text-align: start');
      expect(style).toContain('overflow-wrap: anywhere');
      for (const key of PHYSICAL_STYLE_KEYS) {
        expect(root.style.getPropertyValue(key)).toBe('');
      }
      expect(screen.getByRole('status')).toBeTruthy();
    },
  );
});
