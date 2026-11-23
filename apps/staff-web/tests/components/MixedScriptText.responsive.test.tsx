import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MixedScriptText } from '../../src/components/MixedScriptText';
import { BREAKPOINT_MEDIA_QUERIES } from '../../src/theme/breakpoints';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

/**
 * Deterministic matchMedia stand-in keyed by media query string. One state
 * object is reused per query so the hook's initializer and effect see the same
 * listeners. All text below is synthetic clinical free text.
 */
function installMatchMedia(initial: Record<string, boolean>) {
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
  return {
    fire(query: string, matches: boolean) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

const layouts = {
  compact: { medium: false, expanded: false },
  medium: { medium: true, expanded: false },
  expanded: { medium: true, expanded: true },
} as const;

type Layout = keyof typeof layouts;

function queriesFor(layout: Layout): Record<string, boolean> {
  return {
    [BREAKPOINT_MEDIA_QUERIES.medium]: layouts[layout].medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: layouts[layout].expanded,
  };
}

const LONG_ENGLISH =
  'A deliberately long synthetic clinical free-text value that must wrap at 320 px and at 200 percent zoom without clipping or overlap';
const LONG_ARABIC =
  'قيمة نصية سريرية تجريبية طويلة جداً يجب أن تلتف دون قص أو تداخل عند العرض الضيق';
const MIXED_RUN = 'ليلى Haddad · MAIN CLINIC · 5 mg';

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive MixedScriptText (WEB-07, UX-01)', () => {
  it('keeps one isolated, wrapping run and the correct breakpoint at every layout', () => {
    for (const layout of ['compact', 'medium', 'expanded'] as const) {
      installMatchMedia(queriesFor(layout));
      const { unmount } = render(<MixedScriptText text={LONG_ENGLISH} lang="en" />);

      const run = screen.getByText(LONG_ENGLISH);
      expect(run.tagName).toBe('BDI');
      expect(run.getAttribute('dir')).toBe('auto');
      expect(run.getAttribute('lang')).toBe('en');
      expect(run.textContent).toBe(LONG_ENGLISH);
      expect(run.getAttribute('data-breakpoint')).toBe(layout);

      // Long values wrap with no fixed block size, so nothing is clipped and
      // text stays readable at 200 percent zoom. No clinical text is hidden.
      expect(run.style.whiteSpace).toBe('normal');
      expect(run.style.overflowWrap).toBe('anywhere');
      const style = run.getAttribute('style') ?? '';
      expect(style).not.toContain('display: none');
      expect(style).not.toMatch(/height:\s*\d+px/);
      unmount();
    }
  });

  it('does not introduce any tab stop or reorder the run across breakpoints', () => {
    installMatchMedia(queriesFor('compact'));
    const { container } = render(
      <>
        <MixedScriptText text="Layla Haddad" lang="en" />
        <MixedScriptText text="ليلى حداد" lang="ar" />
      </>,
    );

    const runs = screen.getAllByText(/Haddad|حداد/);
    expect(runs).toHaveLength(2);
    expect(runs[0]?.textContent).toBe('Layla Haddad');
    expect(runs[1]?.textContent).toBe('ليلى حداد');
    for (const run of runs) {
      expect(run.tabIndex).toBe(-1);
    }
    // A text primitive adds no focusable element of its own; the page keeps
    // exactly the keyboard order of its surrounding controls.
    expect(
      container.querySelectorAll('a[href], button, input, select, textarea, [tabindex]'),
    ).toHaveLength(0);
  });

  it('follows a viewport change without remounting or altering the clinical text', () => {
    const media = installMatchMedia(queriesFor('compact'));
    render(<MixedScriptText text={LONG_ARABIC} lang="ar" />);

    const run = screen.getByText(LONG_ARABIC);
    expect(run.getAttribute('data-breakpoint')).toBe('compact');
    expect(run.style.whiteSpace).toBe('normal');
    expect(run.style.overflowWrap).toBe('anywhere');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.medium, true));
    expect(run.getAttribute('data-breakpoint')).toBe('medium');
    expect(run.textContent).toBe(LONG_ARABIC);

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(run.getAttribute('data-breakpoint')).toBe('expanded');
    expect(run.textContent).toBe(LONG_ARABIC);
  });

  it('preserves the mixed-script isolation run and logical styling under right-to-left', () => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    installMatchMedia(queriesFor('compact'));
    render(<MixedScriptText text={MIXED_RUN} lang="ar" />);

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const run = screen.getByText(MIXED_RUN);
    expect(run.tagName).toBe('BDI');
    expect(run.getAttribute('dir')).toBe('auto');
    expect(run.getAttribute('lang')).toBe('ar');
    expect(run.textContent).toBe(MIXED_RUN);

    // Direction comes from the document; the run is never given a direction
    // override, a physical side, or a hidden clinical value.
    expect(run.style.direction).toBe('');
    expect(run.style.whiteSpace).toBe('normal');
    expect(run.style.overflowWrap).toBe('anywhere');
    const style = run.getAttribute('style') ?? '';
    expect(style).not.toMatch(/(?:margin|padding|border|inset)-(?:left|right)\s*:/);
    expect(style).not.toMatch(/text-align\s*:\s*(?:left|right)/);
    expect(style).not.toContain('display: none');
  });
});
