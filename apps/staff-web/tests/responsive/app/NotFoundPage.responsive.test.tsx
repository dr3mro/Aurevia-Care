import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NotFoundPage } from '../../../src/app/NotFoundPage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES, BREAKPOINT_WIDTHS } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type LayoutBand = 'compact' | 'medium' | 'expanded';
type ChangeListener = (event: MediaQueryListEvent) => void;

/** Synthetic media answers for each layout band; no real viewport is measured. */
interface ViewportState {
  medium: boolean;
  expanded: boolean;
  coarse: boolean;
}

/** The three bands mirror the Playwright widths without painting pixels (WEB-07). */
const VIEWPORTS: Record<LayoutBand, ViewportState> = {
  compact: { medium: false, expanded: false, coarse: true },
  medium: { medium: true, expanded: false, coarse: true },
  expanded: { medium: true, expanded: true, coarse: false },
};

/** Physical CSS properties that must never appear in a component style. */
const physicalStyleKeys = [
  'margin-left',
  'margin-right',
  'padding-left',
  'padding-right',
  'border-left',
  'border-right',
  'left',
  'right',
] as const;

/**
 * jsdom cannot evaluate media queries. Install a deterministic stand-in keyed
 * by query string so the real breakpoint hook resolves at each synthetic band.
 */
function installMatchMedia(state: ViewportState): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: state.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: state.expanded,
    '(pointer: coarse)': state.coarse,
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const listeners = new Set<ChangeListener>();
      return {
        matches: answers[query] ?? false,
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: ChangeListener) => {
          listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          listeners.delete(listener);
        },
      } as unknown as MediaQueryList;
    }),
  );
}

/** Render the catch-all page under the providers it uses in the shell. */
function renderPage(locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <NotFoundPage />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The catch-all page root, distinct from the nested state-panel section. */
function pageRoot(): HTMLElement {
  const root = document.querySelector<HTMLElement>('section[aria-labelledby="not-found-title"]');
  if (!root) {
    throw new Error('responsive not-found page root was not rendered');
  }
  return root;
}

/** The single layout container holding the heading and the state panel. */
function stackContainer(root: HTMLElement): HTMLElement {
  const stack = root.firstElementChild;
  if (!(stack instanceof HTMLElement)) {
    throw new Error('not-found page has no layout container');
  }
  return stack;
}

/** The page title, distinct from the heading inside the nested state panel. */
function notFoundHeading(): HTMLElement {
  const heading = document.querySelector<HTMLElement>('#not-found-title');
  if (!heading) {
    throw new Error('not-found page title was not rendered');
  }
  return heading;
}

/** Only the elements this component styles; the nested state panel owns its CSS. */
function styledPageElements(root: HTMLElement): HTMLElement[] {
  return [root, stackContainer(root), notFoundHeading()];
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive NotFoundPage at %s width',
  (band) => {
    it('resolves the band and stacks the labelled heading above the state panel', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPage();

      const root = pageRoot();
      expect(root.getAttribute('data-breakpoint')).toBe(band);
      expect(root.classList.contains('page')).toBe(true);

      const stack = stackContainer(root);
      expect(stack.classList.contains('stack')).toBe(true);
      const heading = notFoundHeading();
      const status = screen.getByRole('status');
      expect(Array.from(stack.children)).toEqual([heading, status]);
      expect(status.getAttribute('aria-live')).toBe('polite');
      expect(status.textContent).toContain('Nothing to show');
    });

    it('keeps the accessible label linking the page to its heading', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPage();

      const root = pageRoot();
      expect(root.getAttribute('aria-labelledby')).toBe('not-found-title');
      const heading = notFoundHeading();
      expect(heading.id).toBe('not-found-title');
      expect(heading.tagName).toBe('H2');
      expect(heading.textContent).toBe('Page not found');
    });

    it('has no tab stops and leaves focus where it was: nothing steals keyboard order', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPage();

      const root = pageRoot();
      const focusable = root.querySelectorAll(
        'a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"])',
      );
      expect(focusable).toHaveLength(0);
      expect(document.activeElement).toBe(document.body);
      const stack = stackContainer(root);
      expect(stack.firstElementChild).toBe(notFoundHeading());
      expect(stack.lastElementChild).toBe(screen.getByRole('status'));
    });

    it('uses logical inline styles only and hides no content with display:none', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPage();

      const root = pageRoot();
      const styled = styledPageElements(root);
      for (const element of styled) {
        expect(element.style.direction).toBe('');
        expect(element.style.display).not.toBe('none');
        for (const key of physicalStyleKeys) {
          expect(element.style.getPropertyValue(key)).toBe('');
        }
      }
      expect(root.querySelector('.hide-compact')).toBeNull();
      expect(root.querySelector('.only-compact')).toBeNull();
      expect(root.style.overflowWrap).toBe('anywhere');
      expect(root.style.textAlign).toBe('start');
      expect(stackContainer(root).style.minInlineSize).toBe('0');
      expect(notFoundHeading().style.overflowWrap).toBe('anywhere');
    });

    it('lets long labels wrap at text zoom without a fixed block size or nowrap', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPage();

      const heading = notFoundHeading();
      expect(heading.style.whiteSpace).not.toBe('nowrap');
      expect(heading.style.display).not.toBe('none');
      expect(heading.style.getPropertyValue('block-size')).toBe('');
      expect(pageRoot().style.getPropertyValue('block-size')).toBe('');
      expect(heading.textContent?.trim().length).toBeGreaterThan(0);
      expect(screen.getByRole('status').textContent?.trim().length).toBeGreaterThan(0);
    });
  },
);

/** Map a synthetic viewport width to the band the hook must report. */
function expectedBand(width: number): LayoutBand {
  if (width >= BREAKPOINT_WIDTHS.expanded) {
    return 'expanded';
  }
  if (width >= BREAKPOINT_WIDTHS.medium) {
    return 'medium';
  }
  return 'compact';
}

describe('responsive NotFoundPage viewport matrix', () => {
  const widths = [320, 360, 390, 768, 820, 1024, 1280] as const;

  it.each(widths)('resolves %ipx to the correct layout band in English', (width) => {
    installMatchMedia({
      medium: width >= BREAKPOINT_WIDTHS.medium,
      expanded: width >= BREAKPOINT_WIDTHS.expanded,
      coarse: width < BREAKPOINT_WIDTHS.expanded,
    });
    renderPage();
    expect(pageRoot().getAttribute('data-breakpoint')).toBe(expectedBand(width));
  });

  it.each(widths)('resolves %ipx to the correct layout band in Arabic', (width) => {
    installMatchMedia({
      medium: width >= BREAKPOINT_WIDTHS.medium,
      expanded: width >= BREAKPOINT_WIDTHS.expanded,
      coarse: width < BREAKPOINT_WIDTHS.expanded,
    });
    const { unmount } = renderPage('ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(pageRoot().getAttribute('data-breakpoint')).toBe(expectedBand(width));
    expect(notFoundHeading().textContent).toBe('الصفحة غير موجودة');
    unmount();
  });
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive NotFoundPage right-to-left at %s width',
  (band) => {
    it('mirrors Arabic text with logical properties and no local direction override', () => {
      installMatchMedia(VIEWPORTS[band]);
      renderPage('ar');

      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');
      const root = pageRoot();
      expect(root.getAttribute('data-breakpoint')).toBe(band);
      const heading = notFoundHeading();
      expect(heading.textContent).toBe('الصفحة غير موجودة');
      expect(heading.getAttribute('dir')).toBeNull();
      expect(screen.getByRole('status').textContent).toContain('لا يوجد شيء لعرضه');

      for (const element of styledPageElements(root)) {
        expect(element.style.direction).toBe('');
        expect(element.style.display).not.toBe('none');
        for (const key of physicalStyleKeys) {
          expect(element.style.getPropertyValue(key)).toBe('');
        }
      }
      expect(root.style.textAlign).toBe('start');
    });
  },
);
