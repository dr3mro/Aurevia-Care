import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TransportError } from '../../src/api/transport';
import {
  ConflictState,
  EmptyState,
  ErrorState,
  ForbiddenState,
  LoadingState,
  SessionExpiredState,
  StateView,
} from '../../src/components/StateViews';
import { I18nProvider } from '../../src/i18n/I18nProvider';
import type { Locale } from '../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../src/theme/breakpoints';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

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

function renderView(locale: Locale, layout: Layout, view: ReactNode) {
  installMatchMedia(layouts[layout]);
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>{view}</I18nProvider>
    </ThemeProvider>,
  );
}

/** The status or alert region every state view renders. */
function stateRegion(): HTMLElement {
  return screen.queryByRole('alert') ?? screen.getByRole('status');
}

/** The shared action row wrapping every state button. */
function actionRow(): HTMLElement {
  const button = screen.getAllByRole('button')[0];
  const wrapper = button?.parentElement;
  if (!wrapper?.parentElement) {
    throw new Error('state view has no action row');
  }
  return wrapper.parentElement;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive StateViews (WEB-07, UX-01)', () => {
  it('centres a phone state view and stacks full-width touch action targets', () => {
    const { unmount } = renderView('en', 'compact', <ErrorState correlationId="SYN-CORR-RESP" />);

    const region = stateRegion();
    expect(region.getAttribute('role')).toBe('alert');
    expect(region.getAttribute('data-breakpoint')).toBe('compact');
    expect(region.className).toContain('page');
    const style = region.getAttribute('style') ?? '';
    expect(style).toContain('place-items: center');
    expect(style).toContain('text-align: start');
    expect(style).toContain('overflow-wrap: anywhere');
    expect(style).toContain('max-inline-size: 44rem');
    expect(style).not.toMatch(/padding-left|padding-right|margin-left|margin-right/);

    const retry = screen.getByRole('button', { name: 'Retry' });
    expect(retry.tagName).toBe('BUTTON');
    expect(retry.parentElement?.className).toContain('touch');
    expect(actionRow().className).toContain('stack');
    unmount();
  });

  it('lays the conflict actions out inline at medium and expanded without losing labels', () => {
    for (const layout of ['medium', 'expanded'] as const) {
      const { unmount } = renderView('en', layout, <ConflictState />);
      const region = stateRegion();
      expect(region.getAttribute('data-breakpoint')).toBe(layout);

      const buttons = screen.getAllByRole('button');
      expect(buttons.map((button) => button.textContent)).toEqual([
        'Compare',
        'Reload',
        'Copy my changes',
      ]);
      expect(actionRow().className).toContain('cluster');
      for (const button of buttons) {
        expect(button.tagName).toBe('BUTTON');
        expect((button as HTMLButtonElement).disabled).toBe(false);
        expect(button.parentElement?.className).toContain('touch');
      }
      unmount();
    }
  });

  it('keeps loading, empty, forbidden and timeout views readable at every layout', () => {
    const timeout = new TransportError('timeout', {
      correlationId: 'SYN-CORR-TIMEOUT',
      code: undefined,
      retryAfterSeconds: undefined,
    });
    const views: readonly (readonly [string, ReactNode])[] = [
      ['loading', <LoadingState key="loading" />],
      ['empty', <EmptyState key="empty" />],
      ['forbidden', <ForbiddenState key="forbidden" />],
      ['timeout', StateView.forTransportError(timeout)],
    ];
    for (const layout of ['compact', 'medium', 'expanded'] as const) {
      for (const [label, view] of views) {
        const { unmount } = renderView('en', layout, view);
        const region = stateRegion();
        expect(region.getAttribute('data-breakpoint')).toBe(layout);
        expect(region.className).toContain('page');
        expect(region.getAttribute('style') ?? '').not.toContain('display: none');
        expect(region.textContent?.trim().length).toBeGreaterThan(0);
        if (label === 'timeout') {
          expect(region.textContent).toContain('Something went wrong');
          expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
        }
        unmount();
      }
    }
  });

  it('preserves the keyboard order of the three conflict actions', () => {
    renderView('en', 'compact', <ConflictState />);
    const buttons = screen.getAllByRole('button');
    expect(buttons.map((button) => button.textContent)).toEqual([
      'Compare',
      'Reload',
      'Copy my changes',
    ]);
    for (const button of buttons) {
      expect(button.tagName).toBe('BUTTON');
      expect((button as HTMLButtonElement).disabled).toBe(false);
      button.focus();
      expect(document.activeElement).toBe(button);
    }
  });

  it('mirrors an Arabic RTL state view with isolated Latin identifiers', () => {
    const conflict = renderView('ar', 'compact', <ConflictState />);
    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('تغيّر هذا السجل');
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual([
      'مقارنة',
      'إعادة تحميل',
      'نسخ تغييراتي',
    ]);
    conflict.unmount();

    const error = renderView('ar', 'medium', <ErrorState correlationId="SYN-CORR-AR" />);
    expect(stateRegion().getAttribute('style')).toContain('text-align: start');
    const code = screen.getByText('SYN-CORR-AR');
    expect(code.tagName).toBe('CODE');
    expect(code.getAttribute('dir')).toBe('ltr');
    error.unmount();

    const expired = renderView('ar', 'expanded', <SessionExpiredState />);
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('انتهت جلستك');
    expect(screen.getByRole('button', { name: 'تسجيل الدخول' })).toBeTruthy();
    expired.unmount();
  });

  it('uses only logical properties on the session-expired panel at every layout', () => {
    for (const layout of ['compact', 'medium', 'expanded'] as const) {
      const { unmount } = renderView('en', layout, <SessionExpiredState />);
      const style = stateRegion().getAttribute('style') ?? '';
      expect(style).not.toMatch(/padding-left|padding-right|margin-left|margin-right/);
      expect(style).toContain('place-items: center');
      expect(screen.getByRole('button', { name: 'Sign in' }).parentElement?.className).toContain(
        'touch',
      );
      unmount();
    }
  });
});
