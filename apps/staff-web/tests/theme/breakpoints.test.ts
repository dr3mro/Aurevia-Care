import { createElement } from 'react';
import { renderToString } from 'react-dom/server';
import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  BREAKPOINT_MEDIA_QUERIES,
  BREAKPOINT_WIDTHS,
  breakpointFor,
} from '../../src/theme/breakpoints';
import { useBreakpoint, useOrientation, usePointerCoarse } from '../../src/theme/useBreakpoint';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

interface MediaFixture {
  state(query: string): QueryState;
  fire(query: string, matches: boolean): void;
}

/** A deterministic window.matchMedia stand-in keyed by media query string. */
function installMatchMedia(initial: Record<string, boolean>): MediaFixture {
  const states = new Map<string, QueryState>();
  const state = (query: string): QueryState => {
    const found = states.get(query);
    if (!found) {
      throw new Error(`matchMedia was not called for ${query}`);
    }
    return found;
  };

  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      const current: QueryState = {
        matches: initial[query] ?? false,
        listeners: new Set<ChangeListener>(),
      };
      states.set(query, current);
      return {
        matches: current.matches,
        addEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          current.listeners.delete(listener);
        },
      };
    }),
  );

  return {
    state,
    fire(query, matches) {
      const current = state(query);
      current.matches = matches;
      for (const listener of current.listeners) {
        listener({ matches } as MediaQueryListEvent);
      }
    },
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('WEB-07 responsive breakpoint tokens', () => {
  it('publishes the documented widths and min-width queries', () => {
    expect(BREAKPOINT_WIDTHS).toEqual({ medium: 768, expanded: 1280 });
    expect(BREAKPOINT_MEDIA_QUERIES).toEqual({
      medium: '(min-width: 768px)',
      expanded: '(min-width: 1280px)',
    });
  });

  it('maps the boundary widths to compact, medium and expanded', () => {
    expect(breakpointFor(767)).toBe('compact');
    expect(breakpointFor(768)).toBe('medium');
    expect(breakpointFor(1279)).toBe('medium');
    expect(breakpointFor(1280)).toBe('expanded');
  });
});

describe('useBreakpoint', () => {
  it('reads the current class and updates when a mocked media query fires', () => {
    const media = installMatchMedia({
      [BREAKPOINT_MEDIA_QUERIES.medium]: true,
      [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
    });
    const { result } = renderHook(() => useBreakpoint());
    expect(result.current).toBe('medium');

    act(() => media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, true));
    expect(result.current).toBe('expanded');

    act(() => {
      media.fire(BREAKPOINT_MEDIA_QUERIES.medium, false);
      media.fire(BREAKPOINT_MEDIA_QUERIES.expanded, false);
    });
    expect(result.current).toBe('compact');
  });

  it('re-syncs a media value that changes between render and subscription', () => {
    const query = '(pointer: coarse)';
    let calls = 0;
    vi.stubGlobal(
      'matchMedia',
      vi.fn((requested: string) => {
        if (requested !== query) {
          throw new Error(`unexpected query ${requested}`);
        }
        // The render's getSnapshot reads `true`; the subscription that follows
        // reads `false`. This reproduces the narrow window where a media change
        // lands after the snapshot but before the change listener is attached,
        // which the subscribe-time check must recover from.
        const matches = calls++ === 0;
        return {
          matches,
          addEventListener: () => undefined,
          removeEventListener: () => undefined,
        };
      }),
    );

    // Without the subscribe-time re-sync the cached snapshot would stay `true`.
    const { result } = renderHook(() => usePointerCoarse());
    expect(result.current).toBe(false);
  });

  it('removes both media query listeners on unmount', () => {
    const media = installMatchMedia({
      [BREAKPOINT_MEDIA_QUERIES.medium]: true,
      [BREAKPOINT_MEDIA_QUERIES.expanded]: false,
    });
    const { unmount } = renderHook(() => useBreakpoint());
    expect(media.state(BREAKPOINT_MEDIA_QUERIES.medium).listeners.size).toBe(1);
    expect(media.state(BREAKPOINT_MEDIA_QUERIES.expanded).listeners.size).toBe(1);

    unmount();

    expect(media.state(BREAKPOINT_MEDIA_QUERIES.medium).listeners.size).toBe(0);
    expect(media.state(BREAKPOINT_MEDIA_QUERIES.expanded).listeners.size).toBe(0);
  });

  it('falls back to expanded when matchMedia does not exist', () => {
    vi.stubGlobal('matchMedia', undefined);
    const { result } = renderHook(() => ({
      breakpoint: useBreakpoint(),
      coarse: usePointerCoarse(),
      orientation: useOrientation(),
    }));
    expect(result.current).toEqual({
      breakpoint: 'expanded',
      coarse: false,
      orientation: 'portrait',
    });
  });

  it('uses the declared fallback snapshot during a server render', () => {
    // On the server useSyncExternalStore calls getServerSnapshot, never getSnapshot
    // or subscribe. The hook must therefore report its fallback without probing
    // matchMedia, which does not exist there.
    vi.stubGlobal('matchMedia', undefined);
    function Probe() {
      return createElement('span', null, useBreakpoint());
    }
    expect(renderToString(createElement(Probe))).toContain('expanded');
  });
});

describe('pointer and orientation hooks', () => {
  it('usePointerCoarse tracks the (pointer: coarse) query', () => {
    const query = '(pointer: coarse)';
    const media = installMatchMedia({ [query]: true });
    const { result } = renderHook(() => usePointerCoarse());
    expect(result.current).toBe(true);

    act(() => media.fire(query, false));
    expect(result.current).toBe(false);
  });

  it('useOrientation tracks the (orientation: portrait) query', () => {
    const query = '(orientation: portrait)';
    const media = installMatchMedia({ [query]: true });
    const { result } = renderHook(() => useOrientation());
    expect(result.current).toBe('portrait');

    act(() => media.fire(query, false));
    expect(result.current).toBe('landscape');
  });
});

describe('useMediaQuery subscription resynchronisation', () => {
  it('refreshes a snapshot that became stale before subscribe ran', () => {
    // React reads getSnapshot during render and only then runs the passive
    // subscribe effect. If the media value changes in that window, subscribe
    // sees a stale snapshot; it must refresh it and notify React so the hook
    // reports the fresh value instead of the one captured during render.
    const listeners = new Set<ChangeListener>();
    let matchMediaCalls = 0;
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => {
        matchMediaCalls += 1;
        const matches = matchMediaCalls === 1 ? false : true;
        return {
          matches,
          addEventListener: (_type: string, listener: ChangeListener) => {
            listeners.add(listener);
          },
          removeEventListener: (_type: string, listener: ChangeListener) => {
            listeners.delete(listener);
          },
        };
      }),
    );

    const { result } = renderHook(() => usePointerCoarse());

    expect(result.current).toBe(true);
  });
});
