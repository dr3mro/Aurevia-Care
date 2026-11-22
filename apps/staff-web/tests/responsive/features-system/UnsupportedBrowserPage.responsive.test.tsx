import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  UnsupportedBrowserPage,
} from '../../../src/features/system/UnsupportedBrowserPage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

const BREAKPOINTS: readonly Breakpoint[] = ['compact', 'medium', 'expanded'];

/**
 * Install a deterministic `matchMedia` stand-in for one layout band.
 *
 * `useBreakpoint` reads only the two `min-width` queries, so an absent query
 * never matches. This exercises the same class the real media queries select
 * without asking jsdom to lay out pixels (that belongs to the Playwright tasks).
 */
function installMatchMedia(breakpoint: Breakpoint): void {
  const matches: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: breakpoint !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: breakpoint === 'expanded',
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: matches[query] ?? false,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

/** Render the page under the providers the entry point supplies. */
function renderPage(locale: Locale, breakpoint: Breakpoint) {
  installMatchMedia(breakpoint);
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <UnsupportedBrowserPage />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The responsive page wrapper that owns layout and safe-area padding. */
function pageWrapper(): HTMLElement {
  const wrapper = document.querySelector('div.page[data-breakpoint]');
  if (!(wrapper instanceof HTMLElement)) {
    throw new Error('UnsupportedBrowserPage has no responsive page wrapper');
  }
  return wrapper;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive UnsupportedBrowserPage (WEB-07, UX-01)', () => {
  it.each(BREAKPOINTS)(
    'keeps one centred page column and a single action at %s',
    (breakpoint) => {
      renderPage('en', breakpoint);

      const wrapper = pageWrapper();
      expect(wrapper.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(wrapper.className.split(/\s+/)).toContain('page');

      const heading = screen.getByRole('heading', { level: 1, name: 'Unsupported browser or screen size' });
      const detail = screen.getByText(
        'This portal needs a supported desktop browser at least 768 pixels wide. No patient information is shown here.',
      );
      const action = screen.getByRole('button', { name: 'Go home' });

      // The wrapper owns the whole page; the notice and its action stay inside.
      expect(wrapper.contains(heading)).toBe(true);
      expect(wrapper.contains(detail)).toBe(true);
      expect(wrapper.contains(action)).toBe(true);
      expect(screen.getAllByRole('button')).toHaveLength(1);

      // No clinical data, navigation or shell chrome on the unsupported page.
      expect(screen.queryByRole('navigation')).toBeNull();
      expect(screen.queryByRole('table')).toBeNull();
      expect(screen.queryByRole('textbox')).toBeNull();

      // The shared touch class supplies both target floors (24px fine, 44px coarse).
      expect(action.className.split(/\s+/)).toContain('touch');
    },
  );

  it.each(BREAKPOINTS)(
    'wraps long English and Arabic text with logical properties at %s',
    (breakpoint) => {
      renderPage('en', breakpoint);
      const style = pageWrapper().getAttribute('style') ?? '';
      expect(style).toContain('overflow-wrap: anywhere');
      expect(style).toContain('text-align: start');
      expect(style).toContain('min-inline-size: 0');
      expect(style).not.toMatch(/padding-left|padding-right|margin-left|margin-right/);
      // No fixed block size, ellipsis or nowrap that could clip scaled text.
      expect(style).not.toContain('text-overflow: ellipsis');
      expect(style).not.toContain('white-space: nowrap');
      expect(style).not.toMatch(/(^|;)\s*height:/);
    },
  );

  it('preserves the single-action keyboard order at every breakpoint', () => {
    for (const breakpoint of BREAKPOINTS) {
      const view = renderPage('en', breakpoint);
      const action = screen.getByRole('button', { name: 'Go home' });
      expect(action.tagName).toBe('BUTTON');
      expect((action as HTMLButtonElement).disabled).toBe(false);
      expect(action.getAttribute('tabindex')).toBeNull();
      action.focus();
      expect(document.activeElement).toBe(action);
      view.unmount();
      vi.unstubAllGlobals();
    }
  });

  it('mirrors the Arabic notice under right-to-left without a direction override', () => {
    renderPage('ar', 'compact');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');

    const wrapper = pageWrapper();
    expect(wrapper.getAttribute('data-breakpoint')).toBe('compact');
    const style = wrapper.getAttribute('style') ?? '';
    expect(style).toContain('text-align: start');
    expect(style).not.toMatch(/padding-left|padding-right|margin-left|margin-right/);

    expect(
      screen.getByRole('heading', { level: 1, name: 'متصفح أو حجم شاشة غير مدعوم' }),
    ).toBeTruthy();
    expect(
      screen.getByText(
        'تتطلب هذه البوابة متصفحاً مكتبياً مدعوماً بعرض 768 بكسل على الأقل. لا يتم عرض أي معلومات عن المرضى هنا.',
      ),
    ).toBeTruthy();
    expect(screen.getByRole('button', { name: 'الصفحة الرئيسية' })).toBeTruthy();
    // Direction comes from the document; no subtree overrides it.
    expect(wrapper.getAttribute('dir')).toBeNull();
    expect(style).not.toContain('direction: rtl');
  });

  it('keeps the document-load action while adding responsive layout', () => {
    const view = renderPage('en', 'medium');
    const action = screen.getByRole('button', { name: 'Go home' });
    // jsdom does not navigate on location.assign; the page must stay put.
    fireEvent.click(action);
    expect(screen.getByRole('heading', { level: 1 })).toBeTruthy();
    view.unmount();
  });
});
