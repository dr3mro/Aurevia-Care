import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SignInPlaceholder } from '../../../src/app/SignInPlaceholder';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';

interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
  portrait: boolean;
}

/** Synthetic viewport states mirroring the responsive Playwright matrix. */
const VIEWPORTS: Record<'compact' | 'medium' | 'expanded', ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true, portrait: true },
  medium: { medium: true, expanded: false, coarse: true, portrait: false },
  expanded: { medium: true, expanded: true, coarse: false, portrait: false },
};

const EN_HEADING = 'Sign in';
const EN_NOTE = 'Identity provider placeholder. No credentials are collected here.';
const AR_HEADING = 'تسجيل الدخول';
const AR_NOTE = 'نموذج تسجيل الدخول عبر Identity provider؛ لا يتم جمع بيانات الدخول هنا.';

/** Physical style keys that must never appear; logical properties only (UX-01). */
const physicalStyleKeys = [
  'margin-left',
  'margin-right',
  'margin-top',
  'margin-bottom',
  'padding-left',
  'padding-right',
  'padding-top',
  'padding-bottom',
  'border-left',
  'border-right',
  'border-top',
  'border-bottom',
  'left',
  'right',
  'top',
  'bottom',
];

/**
 * jsdom has no media-query engine. Install a deterministic stand-in keyed by
 * query so the real breakpoint hook resolves at each synthetic width.
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
      dispatchEvent: () => true,
    })),
  );
}

function renderPlaceholder(locale: 'en' | 'ar' = 'en') {
  return render(
    <I18nProvider initialLocale={locale}>
      <SignInPlaceholder />
    </I18nProvider>,
  );
}

/** The section carrying the responsive contract for the placeholder. */
function placeholderRoot(): HTMLElement {
  const element = document.querySelector('[data-breakpoint]');
  if (!element) {
    throw new Error('responsive sign-in placeholder root was not rendered');
  }
  return element as HTMLElement;
}

/** Every element in the placeholder that carries an inline style. */
function styledElements(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>('[style]')).concat(root);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive SignInPlaceholder at %s width',
  (layout) => {
    it('keeps the labelled heading and note in one stacked column in a stable order', () => {
      installMatchMedia(VIEWPORTS[layout]);
      renderPlaceholder();

      const root = placeholderRoot();
      expect(root.getAttribute('data-breakpoint')).toBe(layout);
      // `page` supplies the single-column grid, safe-area logical padding and
      // the centred reading measure at every width.
      expect(root.classList.contains('page')).toBe(true);
      expect(screen.getByRole('heading', { level: 2, name: EN_HEADING })).toBeTruthy();
      expect(screen.getByText(EN_NOTE)).toBeTruthy();

      // DOM order is the reading and keyboard order; it never changes with width.
      expect(Array.from(root.children).map((child) => child.tagName)).toEqual(['H2', 'P']);
      // No credential field is introduced at any width: identity tooling owns auth.
      expect(screen.queryByLabelText(/password/i)).toBeNull();
      expect(screen.queryByLabelText(/email/i)).toBeNull();
      expect(
        root.querySelectorAll('input, button, select, textarea, a[href], [tabindex]'),
      ).toHaveLength(0);
    });

    it('wraps long text with no fixed height, no hidden content and logical properties only', () => {
      installMatchMedia(VIEWPORTS[layout]);
      renderPlaceholder();

      const root = placeholderRoot();
      for (const element of styledElements(root)) {
        expect(element.style.direction).toBe('');
        expect(element.style.display).not.toBe('none');
        expect(element.style.whiteSpace).not.toBe('nowrap');
        const style = element.getAttribute('style') ?? '';
        expect(style).not.toContain('display: none');
        expect(style).not.toMatch(/height:\s*\d+px/);
        for (const key of physicalStyleKeys) {
          expect(element.style.getPropertyValue(key)).toBe('');
        }
      }

      const style = root.getAttribute('style') ?? '';
      // No dedicated placeholder class exists in responsive.css, so wrapping
      // and the 16px-safe reading geometry use logical inline styles.
      expect(style).toContain('overflow-wrap: anywhere');
      expect(style).toContain('min-inline-size: 0');
      expect(style).toContain('text-align: start');
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
    });
  },
);

describe('responsive SignInPlaceholder width matrix', () => {
  it.each([320, 360, 390, 768, 820, 1024, 1280] as const)(
    'resolves %ipx to the right layout band',
    (width) => {
      installMatchMedia({
        medium: width >= BREAKPOINT_WIDTHS.medium,
        expanded: width >= BREAKPOINT_WIDTHS.expanded,
        coarse: width < BREAKPOINT_WIDTHS.expanded,
        portrait: width < BREAKPOINT_WIDTHS.medium,
      });
      renderPlaceholder();

      const expected =
        width >= BREAKPOINT_WIDTHS.expanded
          ? 'expanded'
          : width >= BREAKPOINT_WIDTHS.medium
            ? 'medium'
            : 'compact';
      expect(placeholderRoot().getAttribute('data-breakpoint')).toBe(expected);
    },
  );
});

describe('responsive SignInPlaceholder right-to-left', () => {
  it('mirrors Arabic text with no direction override and no hidden field', () => {
    installMatchMedia(VIEWPORTS.compact);
    renderPlaceholder('ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe(AR_HEADING);
    expect(screen.getByText(AR_NOTE)).toBeTruthy();

    const root = placeholderRoot();
    expect(root.getAttribute('data-breakpoint')).toBe('compact');
    for (const element of styledElements(root)) {
      expect(element.style.direction).toBe('');
      expect(element.style.display).not.toBe('none');
      expect(element.getAttribute('style') ?? '').not.toMatch(/text-align\s*:\s*(?:left|right)/);
      for (const key of physicalStyleKeys) {
        expect(element.style.getPropertyValue(key)).toBe('');
      }
    }
    expect(screen.queryByLabelText(/كلمة المرور|password/i)).toBeNull();
  });
});
