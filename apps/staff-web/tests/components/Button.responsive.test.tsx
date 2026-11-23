import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, render, renderHook, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { Button } from '../../src/components/Button';
import { BREAKPOINT_MEDIA_QUERIES } from '../../src/theme/breakpoints';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { useBreakpoint, usePointerCoarse } from '../../src/theme/useBreakpoint';

type MatchListener = (event: MediaQueryListEvent) => void;

interface FakeMediaQueryList {
  media: string;
  matches: boolean;
  onchange: null;
  addEventListener: (type: string, listener: MatchListener) => void;
  removeEventListener: (type: string, listener: MatchListener) => void;
  addListener: (listener: MatchListener) => void;
  removeListener: (listener: MatchListener) => void;
  dispatchEvent: (event: Event) => boolean;
}

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

/**
 * jsdom has no `matchMedia`. Install a fake whose answers follow the synthetic
 * viewport state so the real breakpoint hooks resolve at each width.
 */
function installMatchMedia(state: ViewportState): (query: string, matches: boolean) => void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: state.medium,
    [BREAKPOINT_MEDIA_QUERIES.expanded]: state.expanded,
    '(pointer: coarse)': state.coarse,
    '(orientation: portrait)': state.portrait,
  };
  const listeners = new Map<string, Set<MatchListener>>();
  const matchMedia = (query: string): MediaQueryList => {
    const queryListeners = listeners.get(query) ?? new Set<MatchListener>();
    listeners.set(query, queryListeners);
    const list: FakeMediaQueryList = {
      media: query,
      matches: answers[query] ?? false,
      onchange: null,
      addEventListener: (_type, listener) => queryListeners.add(listener),
      removeEventListener: (_type, listener) => queryListeners.delete(listener),
      addListener: (listener) => queryListeners.add(listener),
      removeListener: (listener) => queryListeners.delete(listener),
      dispatchEvent: () => true,
    };
    return list as unknown as MediaQueryList;
  };
  vi.stubGlobal('matchMedia', matchMedia);
  return (query, matches) => {
    answers[query] = matches;
    for (const listener of listeners.get(query) ?? []) {
      listener({ matches, media: query } as MediaQueryListEvent);
    }
  };
}

function renderButton(ui: ReactNode): void {
  render(<ThemeProvider>{ui}</ThemeProvider>);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['compact', 'medium', 'expanded'] as const)(
  'responsive Button at %s width',
  (breakpoint) => {
    it('resolves the breakpoint and keeps an unhidden native button in DOM order', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      // No case inherits dir/lang from an earlier case's RTL assertions.
      expect(document.documentElement.dir).toBe('');
      expect(document.documentElement.lang).toBe('');
      expect(renderHook(() => useBreakpoint()).result.current).toBe(breakpoint);

      renderButton(
        <>
          <Button>Save</Button>
          <Button variant="secondary">Cancel</Button>
        </>,
      );

      const buttons = screen.getAllByRole('button');
      expect(buttons.map((button) => button.textContent)).toEqual(['Save', 'Cancel']);
      for (const button of buttons) {
        expect(button.tagName).toBe('BUTTON');
        expect(button.tabIndex).toBe(0);
        expect(button.hasAttribute('hidden')).toBe(false);
        expect(button.style.display).toBe('');
        expect(button.getAttribute('data-breakpoint')).toBe(breakpoint);
        expect(button.classList.contains('touch')).toBe(true);
      }
      buttons[0]?.focus();
      expect(document.activeElement).toBe(buttons[0]);
      buttons[1]?.focus();
      expect(document.activeElement).toBe(buttons[1]);
    });

    it('keeps the label, variant, type, pressed state and disabled state', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      renderButton(
        <>
          <Button pressed>Save patient</Button>
          <Button variant="secondary" pressed={false} type="submit">
            Cancel
          </Button>
          <Button disabled>Unavailable</Button>
          <Button>Untouched</Button>
        </>,
      );

      const primary = screen.getByRole('button', { name: 'Save patient' });
      const secondary = screen.getByRole('button', { name: 'Cancel' });
      const unavailable = screen.getByRole<HTMLButtonElement>('button', { name: 'Unavailable' });
      const untouched = screen.getByRole('button', { name: 'Untouched' });

      expect(untouched.hasAttribute('aria-pressed')).toBe(false);
      expect(primary.getAttribute('aria-pressed')).toBe('true');
      expect(primary.getAttribute('data-variant')).toBe('primary');
      expect(primary.getAttribute('type')).toBe('button');
      expect(secondary.getAttribute('aria-pressed')).toBe('false');
      expect(secondary.getAttribute('data-variant')).toBe('secondary');
      expect(secondary.getAttribute('type')).toBe('submit');
      expect(unavailable.disabled).toBe(true);
    });

    it('wraps a long English label with logical inline styles', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      const label = 'Verify and release the SYN-0001 appointment for the 21 November 2026 clinic';
      renderButton(<Button>{label}</Button>);

      const button = screen.getByRole('button', { name: label });
      expect(button.textContent).toBe(label);
      expect(button.style.overflowWrap).toBe('anywhere');
      expect(button.style.whiteSpace).toBe('normal');
      expect(button.style.textAlign).toBe('start');
      expect(button.style.paddingBlock).toBe('4px');
      expect(button.style.paddingInline).toBe('8px');
      expect(button.style.scrollMarginBlockEnd).toBe('16px');
      expect(button.style.minHeight).toBe('');
      expect(button.style.paddingLeft).toBe('');
      expect(button.style.paddingRight).toBe('');
      expect(button.style.paddingTop).toBe('');
      expect(button.style.paddingBottom).toBe('');
      // Structure check, never a painted-pixel claim (design §13).
      expect(button.getAttribute('style')).not.toMatch(
        /(?:padding|margin)-(?:left|right|top|bottom)\s*:|(?:^|;)\s*(?:left|right|top|bottom)\s*:/,
      );
    });

    it('opts into the stylesheet target without an inline size override', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      expect(renderHook(() => usePointerCoarse()).result.current).toBe(
        VIEWPORTS[breakpoint].coarse,
      );
      renderButton(<Button>Save</Button>);

      const button = screen.getByRole('button', { name: 'Save' });
      expect(button.classList.contains('touch')).toBe(true);
      expect(button.style.minBlockSize).toBe('');
      expect(button.style.minInlineSize).toBe('');
    });

    it('leaves the touch class CSS-driven when the pointer query changes', () => {
      const fire = installMatchMedia(VIEWPORTS[breakpoint]);
      const { result } = renderHook(() => usePointerCoarse());
      act(() => fire('(pointer: coarse)', !result.current));
      expect(result.current).toBe(!VIEWPORTS[breakpoint].coarse);

      renderButton(<Button>Save</Button>);
      const button = screen.getByRole('button', { name: 'Save' });
      expect(button.classList.contains('touch')).toBe(true);
      expect(button.getAttribute('data-breakpoint')).toBe(breakpoint);
    });

    it('keeps an Arabic label readable under right-to-left without physical sides', () => {
      installMatchMedia(VIEWPORTS[breakpoint]);
      document.documentElement.dir = 'rtl';
      document.documentElement.lang = 'ar';
      const label = 'تأكيد وتحرير موعد المريض SYN-0001 في العيادة';
      renderButton(<Button>{label}</Button>);

      const button = screen.getByRole('button', { name: label });
      expect(document.documentElement.dir).toBe('rtl');
      expect(button.textContent).toBe(label);
      expect(button.getAttribute('dir')).toBeNull();
      expect(button.style.direction).toBe('');
      expect(button.style.textAlign).toBe('start');
      expect(button.style.overflowWrap).toBe('anywhere');
      expect(button.style.paddingInline).toBe('8px');
      expect(button.style.paddingLeft).toBe('');
      expect(button.style.paddingRight).toBe('');
      expect(button.getAttribute('style')).not.toMatch(
        /(?:padding|margin)-(?:left|right|top|bottom)\s*:|(?:^|;)\s*(?:left|right|top|bottom)\s*:/,
      );
      expect(button.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(button.classList.contains('touch')).toBe(true);
    });
  },
);
