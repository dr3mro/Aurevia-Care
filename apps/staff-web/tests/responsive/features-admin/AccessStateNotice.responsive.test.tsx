import { act, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AccessStateNotice } from '../../../src/features/admin/AccessStateNotice';
import type { AccessState } from '../../../src/features/admin/domain';
import { accessStateMessageKey, adminText } from '../../../src/features/admin/messages';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import type { Locale } from '../../../src/i18n/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type ChangeListener = (event: MediaQueryListEvent) => void;
type Breakpoint = 'compact' | 'medium' | 'expanded';

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query string. One state
 * object is reused per query so the breakpoint hook's initializer and effect
 * observe the same listeners. All text below is synthetic clinical free text.
 */
function installMatchMedia(initial: Record<string, boolean>): (query: string, matches: boolean) => void {
  const states = new Map<string, QueryState>();
  const state = (query: string): QueryState => {
    let current = states.get(query);
    if (!current) {
      current = { matches: initial[query] ?? false, listeners: new Set() };
      states.set(query, current);
    }
    return current;
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      get matches() {
        return state(query).matches;
      },
      addEventListener: (_type: string, listener: ChangeListener) => {
        state(query).listeners.add(listener);
      },
      removeEventListener: (_type: string, listener: ChangeListener) => {
        state(query).listeners.delete(listener);
      },
    })),
  );
  return (query, matches) => {
    const current = state(query);
    current.matches = matches;
    for (const listener of current.listeners) {
      listener({ matches } as MediaQueryListEvent);
    }
  };
}

/** Synthetic media answers for the three layout bands (no viewport is measured). */
const layouts: Record<Breakpoint, Record<string, boolean>> = {
  compact: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: false,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
  },
  medium: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: true,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
  },
  expanded: {
    [BREAKPOINT_MEDIA_QUERIES.medium]: true,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: true,
  },
};

const LONG_ENGLISH =
  'A deliberately long synthetic access-review label that must wrap at 320 px and at 200 percent zoom without clipping, truncation or overlap';
const LONG_ARABIC = 'تسمية مراجعة وصول تجريبية طويلة جداً يجب أن تلتف دون قص أو تداخل عند العرض الضيق';

/** Render one notice under the real providers with a synthetic viewport. */
function renderNotice(
  state: AccessState,
  label: string,
  breakpoint: Breakpoint,
  locale: Locale = 'en',
) {
  installMatchMedia(layouts[breakpoint]);
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <AccessStateNotice state={state} label={label} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

/** The single state-bearing region, independent of its alert/status role. */
function notice(): HTMLElement {
  const element = document.querySelector('[data-access-state]');
  if (!element) {
    throw new Error('AccessStateNotice rendered no state-bearing region');
  }
  return element as HTMLElement;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive AccessStateNotice at %s width (WEB-07, UX-01)',
  (breakpoint) => {
    it('exposes the band and keeps one state, role and reading order in every access state', () => {
      for (const state of ['permitted', 'denied', 'concealed'] as const) {
        const { unmount } = renderNotice(state, 'Synthetic probe SYN-PROBE-01', breakpoint);

        const region = notice();
        expect(region.getAttribute('data-access-state')).toBe(state);
        expect(region.getAttribute('data-breakpoint')).toBe(breakpoint);
        expect(region.getAttribute('role')).toBe(state === 'denied' ? 'alert' : 'status');
        // The two fields stack on a phone and sit inline from the medium band up.
        expect(region.className).toContain(breakpoint === 'compact' ? 'stack' : 'cluster');

        const badge = region.querySelector('[data-state]') as HTMLElement;
        expect(badge).toBeTruthy();
        expect(badge.getAttribute('data-state')).toBe(state);
        expect(badge.querySelector('[aria-hidden="true"]')?.textContent).toBe(
          state === 'permitted' ? '✓' : state === 'denied' ? '×' : '—',
        );

        const runs = region.querySelectorAll('bdi');
        expect(runs).toHaveLength(2);
        // Badge message first, then the reviewed label: stable DOM/reading order.
        expect(runs[0]?.textContent).toBe(adminText('en', accessStateMessageKey[state]));
        expect(runs[1]?.textContent).toBe('Synthetic probe SYN-PROBE-01');
        expect(runs[0]?.getAttribute('dir')).toBe('auto');
        expect(runs[1]?.getAttribute('dir')).toBe('auto');
        unmount();
      }
    });

    it('uses safe-area-aware logical padding with no hidden, clipped or oversized value', () => {
      renderNotice('permitted', 'Synthetic probe SYN-PROBE-01', breakpoint);

      const region = notice();
      const style = region.getAttribute('style') ?? '';
      for (const property of [
        'padding-block-start',
        'padding-block-end',
        'padding-inline-start',
        'padding-inline-end',
      ]) {
        expect(style).toContain(property);
      }
      expect(style).toContain('--safe-block-start');
      expect(style).toContain('--safe-block-end');
      expect(style).toContain('--safe-inline-start');
      expect(style).toContain('--safe-inline-end');
      // A zero inline minimum and anywhere wrapping prevent a page scroll.
      expect(style).toContain('min-inline-size: 0');
      expect(style).toContain('overflow-wrap: anywhere');
      // No fixed block size: text stays readable at 200 percent zoom.
      expect(style).not.toMatch(/block-size:\s*\d+px/);
      expect(style).not.toContain('display: none');
      expect(style).not.toMatch(/(?:padding|margin|border|inset)-(?:left|right|top|bottom)\s*:/);
      expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
    });

    it('adds no tab stop and never hides a clinical value behind a control', () => {
      const { container } = renderNotice('denied', 'Synthetic probe SYN-PROBE-01', breakpoint);

      // The notice is a status region, not a control: it preserves the surrounding
      // keyboard order and introduces no interactive target to mis-size.
      expect(
        container.querySelectorAll('a[href], button, input, select, textarea, [tabindex]'),
      ).toHaveLength(0);
      expect(notice().querySelector('[aria-hidden="true"]')?.getAttribute('aria-hidden')).toBe(
        'true',
      );
      expect(notice().textContent).toContain('Synthetic probe SYN-PROBE-01');
    });

    it('wraps long English and Arabic labels without truncation or a direction override', () => {
      for (const [label, locale] of [
        [LONG_ENGLISH, 'en'],
        [LONG_ARABIC, 'ar'],
      ] as const) {
        const { unmount } = renderNotice('permitted', label, breakpoint, locale);

        const region = notice();
        expect(region.getAttribute('data-breakpoint')).toBe(breakpoint);
        const value = region.querySelectorAll('bdi')[1] as HTMLElement;
        expect(value.textContent).toBe(label);
        expect(value.tagName).toBe('BDI');
        expect(value.getAttribute('dir')).toBe('auto');
        // The reviewed label keeps its authored `lang="en"` at every locale;
        // the translated message run below carries the active locale instead.
        expect(value.getAttribute('lang')).toBe('en');
        expect((region.querySelectorAll('bdi')[0] as HTMLElement).getAttribute('lang')).toBe(locale);
        expect(value.style.whiteSpace).toBe('normal');
        expect(value.style.overflowWrap).toBe('anywhere');
        expect(value.getAttribute('style')).not.toContain('display: none');
        expect(value.getAttribute('style')).not.toMatch(/block-size:\s*\d+px/);
        unmount();
      }
    });
  },
);

describe('responsive AccessStateNotice direction and continuity (WEB-07, UX-01)', () => {
  it('mirrors under right-to-left with the Arabic state message and no physical sides', () => {
    for (const breakpoint of ['compact', 'medium', 'expanded'] as const) {
      const { unmount } = renderNotice('denied', LONG_ARABIC, breakpoint, 'ar');

      expect(document.documentElement.dir).toBe('rtl');
      expect(document.documentElement.lang).toBe('ar');
      const region = notice();
      expect(region.getAttribute('role')).toBe('alert');
      expect(region.getAttribute('data-breakpoint')).toBe(breakpoint);
      const message = region.querySelectorAll('bdi')[0] as HTMLElement;
      expect(message.textContent).toBe(adminText('ar', 'accessDenied'));
      expect(message.getAttribute('dir')).toBe('auto');
      expect(message.style.direction).toBe('');
      const style = region.getAttribute('style') ?? '';
      expect(style).toContain('padding-inline-start');
      expect(style).toContain('padding-inline-end');
      expect(style).not.toMatch(/(?:padding|margin|border|inset)-(?:left|right)\s*:/);
      expect(style).not.toContain('display: none');
      unmount();
    }
  });

  it('follows a viewport change without remounting or altering the clinical label', () => {
    const fire = installMatchMedia(layouts.compact);
    const { container } = render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <AccessStateNotice state="permitted" label={LONG_ENGLISH} />
        </I18nProvider>
      </ThemeProvider>,
    );

    const initial = notice();
    expect(initial.getAttribute('data-breakpoint')).toBe('compact');
    expect(initial.className).toContain('stack');
    expect((initial.querySelectorAll('bdi')[1] as HTMLElement).textContent).toBe(LONG_ENGLISH);

    act(() => fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(notice()).toBe(initial);
    expect(initial.getAttribute('data-breakpoint')).toBe('medium');
    expect(initial.className).toContain('cluster');

    act(() => fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(notice()).toBe(initial);
    expect(initial.getAttribute('data-breakpoint')).toBe('expanded');
    expect((initial.querySelectorAll('bdi')[1] as HTMLElement).textContent).toBe(LONG_ENGLISH);
    expect(container.querySelectorAll('bdi')).toHaveLength(2);
  });
});
