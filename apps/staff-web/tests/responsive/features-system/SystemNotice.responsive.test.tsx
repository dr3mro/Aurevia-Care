import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SystemNotice } from '../../../src/features/system/SystemNotice';
import type { SystemNoticeProps } from '../../../src/features/system/SystemNotice';
import { systemMessages } from '../../../src/features/system/messages';
import type { SystemMessageKey } from '../../../src/features/system/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type Layout = 'compact' | 'medium' | 'expanded';

/** Synthetic media answers for the three layout bands; no viewport is measured. */
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

/** Install a deterministic matchMedia stand-in keyed by media query string. */
function installMatchMedia(initial: Record<string, boolean>): void {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: initial[query] ?? false,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

interface NoticeKeys {
  titleKey: SystemMessageKey;
  detailKey: SystemMessageKey;
  actionKey: SystemMessageKey;
}

/** Render the notice under its providers in a chosen locale and layout band. */
function renderNotice(
  locale: Locale,
  layout: Layout,
  keys: NoticeKeys,
  onAction: () => void = vi.fn(),
) {
  installMatchMedia(layouts[layout]);
  const props: SystemNoticeProps = { ...keys, onAction };
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <SystemNotice {...props} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The labelled section landmark that wraps one system notice. */
function noticeRegion(title: string): HTMLElement {
  return screen.getByRole('region', { name: title });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive SystemNotice (WEB-07, UX-01)', () => {
  const en = systemMessages.en;
  const ar = systemMessages.ar;
  const signedOut: NoticeKeys = {
    titleKey: 'signedOutTitle',
    detailKey: 'signedOutDetail',
    actionKey: 'signIn',
  };
  const unsupported: NoticeKeys = {
    titleKey: 'unsupportedTitle',
    detailKey: 'unsupportedDetail',
    actionKey: 'goHome',
  };

  it.each(['compact', 'medium', 'expanded'] as const)(
    'keeps a labelled, stacked notice with one touch action at the %s layout',
    (layout) => {
      renderNotice('en', layout, signedOut);
      const region = noticeRegion(en.signedOutTitle);

      // The resolved layout is observable in jsdom even though a real media
      // query cannot be evaluated there (R3 of the responsive contract).
      expect(region.getAttribute('data-breakpoint')).toBe(layout);
      expect(region.className).toContain('page');
      expect(region.className).toContain('stack');
      expect(region.querySelector('h1')?.textContent).toBe(en.signedOutTitle);
      expect(region.querySelector('p')?.textContent).toBe(en.signedOutDetail);

      const button = screen.getByRole('button', { name: en.signIn });
      expect(screen.getAllByRole('button')).toHaveLength(1);
      expect(button.tagName).toBe('BUTTON');
      expect(button.getAttribute('data-breakpoint')).toBe(layout);
      expect(button.className).toContain('touch');
    },
  );

  it.each(['compact', 'medium', 'expanded'] as const)(
    'uses only logical inline styles and hides no content at the %s layout',
    (layout) => {
      renderNotice('en', layout, signedOut);
      const style = noticeRegion(en.signedOutTitle).getAttribute('style') ?? '';
      expect(style).toContain('overflow-wrap: anywhere');
      expect(style).toContain('text-align: start');
      expect(style).toContain('min-inline-size: 0');
      expect(style).not.toMatch(/padding-left|padding-right|margin-left|margin-right/);
      expect(style).not.toContain('display: none');
      expect(screen.queryByText(en.signedOutDetail)).toBeTruthy();
    },
  );

  it('keeps the long English and Arabic strings wrappable in every band', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const layout of ['compact', 'medium', 'expanded'] as const) {
        const view = renderNotice(locale, layout, unsupported);
        const region = noticeRegion(systemMessages[locale].unsupportedTitle);
        for (const tag of ['h1', 'p']) {
          const element = region.querySelector(tag);
          const style = element?.getAttribute('style') ?? '';
          expect(style).toContain('overflow-wrap: anywhere');
          expect(style).toContain('white-space: normal');
          expect(style).not.toMatch(/text-align: (left|right)/);
        }
        expect(region.querySelector('p')?.textContent).toBe(
          systemMessages[locale].unsupportedDetail,
        );
        view.unmount();
      }
    }
  });

  it('keeps the single action last in the DOM and focus order and fires it', () => {
    const onAction = vi.fn();
    renderNotice('en', 'compact', signedOut, onAction);
    const region = noticeRegion(en.signedOutTitle);
    expect(Array.from(region.children).map((child) => child.tagName)).toEqual([
      'H1',
      'P',
      'BUTTON',
    ]);
    const button = screen.getByRole('button', { name: en.signIn });
    button.focus();
    expect(document.activeElement).toBe(button);
    fireEvent.click(button);
    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it('mirrors an Arabic RTL notice without a subtree direction override', () => {
    for (const layout of ['compact', 'medium', 'expanded'] as const) {
      const view = renderNotice('ar', layout, unsupported);
      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');
      const region = noticeRegion(ar.unsupportedTitle);
      expect(region.getAttribute('data-breakpoint')).toBe(layout);
      expect(region.getAttribute('style')).toContain('text-align: start');
      expect(region.getAttribute('style')).not.toMatch(/padding-left|padding-right/);
      expect(region.hasAttribute('dir')).toBe(false);
      expect(region.querySelector('h1')?.textContent).toBe(ar.unsupportedTitle);
      expect(screen.getByRole('button', { name: ar.goHome })).toBeTruthy();
      view.unmount();
    }
  });
});
