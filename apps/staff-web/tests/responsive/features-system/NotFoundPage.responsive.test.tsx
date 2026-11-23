import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NotFoundPage } from '../../../src/features/system/NotFoundPage';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import type { Breakpoint } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

const BREAKPOINTS: readonly Breakpoint[] = ['compact', 'medium', 'expanded'];

/**
 * Install a deterministic `matchMedia` stand-in for one layout band. The
 * responsive hooks only read the two `min-width` queries and the coarse-pointer
 * query; an unlisted query never matches. No viewport is measured in jsdom.
 */
function installMatchMedia(breakpoint: Breakpoint): void {
  const matches: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: breakpoint !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: breakpoint === 'expanded',
    '(pointer: coarse)': breakpoint === 'compact',
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

/** Render the data-free notice under the providers it needs, in one locale. */
function renderNotice(locale: Locale = 'en') {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <NotFoundPage />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The responsive page utility is the ancestor that carries `page` and the band. */
function noticePage(): HTMLElement {
  const heading = screen.getByRole('heading', { level: 1 });
  const page = heading.closest('.page')?.parentElement?.closest('.page');
  if (!page) {
    throw new Error('not-found notice is not inside a responsive page wrapper');
  }
  return page as HTMLElement;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive NotFoundPage (WEB-07, UX-01)', () => {
  it.each(BREAKPOINTS)('centres the notice and wraps long text at %s', (breakpoint) => {
    installMatchMedia(breakpoint);
    const view = renderNotice();

    const page = noticePage();
    expect(page.classList.contains('page')).toBe(true);
    expect(page.getAttribute('data-breakpoint')).toBe(breakpoint);
    const style = page.getAttribute('style') ?? '';
    expect(style).toContain('place-items: center');
    expect(style).toContain('text-align: start');
    expect(style).toContain('overflow-wrap: anywhere');
    expect(style).not.toMatch(/padding-left|padding-right|margin-left|margin-right/);
    expect(style).not.toContain('display: none');

    const home = screen.getByRole('button', { name: 'Go home' });
    expect(home.tagName).toBe('BUTTON');
    // The stylesheet's .touch rule owns the 44px coarse-pointer floor; an
    // inline min-size would outrank it, so the button must not set one.
    expect(home.className).toContain('touch');
    expect(home.getAttribute('data-breakpoint')).toBe(breakpoint);
    const buttonStyle = home.getAttribute('style') ?? '';
    expect(buttonStyle).toContain('white-space: normal');
    expect(buttonStyle).toContain('overflow-wrap: anywhere');
    expect(buttonStyle).not.toMatch(/min-height|min-width|padding-left|padding-right/);

    view.unmount();
  });

  it.each(BREAKPOINTS)(
    'keeps the labelled region, title, detail and one action at %s',
    (breakpoint) => {
      installMatchMedia(breakpoint);
      const view = renderNotice();

      expect(screen.getByRole('region', { name: 'Page not found' })).toBeTruthy();
      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Page not found');
      expect(
        screen.getByText('The page you requested does not exist. Go home to continue.'),
      ).toBeTruthy();
      expect(screen.getAllByRole('button')).toHaveLength(1);
      expect(screen.getByRole('button', { name: 'Go home' })).toBeTruthy();

      view.unmount();
    },
  );

  it('keeps the single action first and keyboard-focusable at every breakpoint', () => {
    for (const breakpoint of BREAKPOINTS) {
      installMatchMedia(breakpoint);
      const view = renderNotice();
      const buttons = screen.getAllByRole('button');
      expect(buttons).toHaveLength(1);

      const home = buttons[0] as HTMLButtonElement;
      expect(home.getAttribute('tabindex')).toBeNull();
      expect(home.disabled).toBe(false);
      home.focus();
      expect(document.activeElement).toBe(home);

      view.unmount();
      vi.unstubAllGlobals();
    }
  });

  it('mirrors the notice in Arabic right-to-left without a direction override', () => {
    installMatchMedia('compact');
    const view = renderNotice('ar');

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(noticePage().getAttribute('data-breakpoint')).toBe('compact');
    expect(noticePage().getAttribute('style')).not.toContain('direction:');
    expect(screen.getByRole('region', { name: 'الصفحة غير موجودة' })).toBeTruthy();
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('الصفحة غير موجودة');
    expect(screen.getByRole('button', { name: 'الصفحة الرئيسية' })).toBeTruthy();

    view.unmount();
  });
});
