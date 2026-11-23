import { useCallback, useRef, useSyncExternalStore } from 'react';
import { getMatchMedia } from './matchMedia';
import { BREAKPOINT_MEDIA_QUERIES } from './breakpoints';
import type { Breakpoint } from './breakpoints';

/**
 * Read one media query and follow its changes. When `matchMedia` is unavailable
 * (server render or older test environments) the supplied fallback is used and
 * no listener is registered.
 *
 * @param query - CSS media query string to observe.
 * @param fallback - Value to report when `matchMedia` does not exist.
 * @returns The current match state.
 */
function useMediaQuery(query: string, fallback: boolean): boolean {
  // The last value reported for `query`. A change event carries the new state,
  // so the snapshot never re-queries the environment between events.
  const latest = useRef<{ query: string; matches: boolean } | null>(null);

  const subscribe = useCallback(
    (onChange: () => void) => {
      const list = getMatchMedia()?.(query);
      if (!list) {
        return () => undefined;
      }
      const onMediaChange = (event: MediaQueryListEvent): void => {
        latest.current = { query, matches: event.matches };
        onChange();
      };
      list.addEventListener('change', onMediaChange);
      if (latest.current?.query !== query || latest.current.matches !== list.matches) {
        latest.current = { query, matches: list.matches };
        onChange();
      }
      return () => list.removeEventListener('change', onMediaChange);
    },
    [query],
  );
  const getSnapshot = (): boolean => {
    if (latest.current?.query !== query) {
      latest.current = { query, matches: getMatchMedia()?.(query).matches ?? fallback };
    }
    return latest.current.matches;
  };
  return useSyncExternalStore(subscribe, getSnapshot, () => fallback);
}

/**
 * Report the responsive layout class for the current viewport. Falls back to
 * `expanded` when `matchMedia` is unavailable.
 */
export function useBreakpoint(): Breakpoint {
  const medium = useMediaQuery(BREAKPOINT_MEDIA_QUERIES.medium, true);
  const expanded = useMediaQuery(BREAKPOINT_MEDIA_QUERIES.expanded, true);
  if (expanded) {
    return 'expanded';
  }
  if (medium) {
    return 'medium';
  }
  return 'compact';
}

/**
 * Report whether the primary pointing device is coarse. Falls back to `false`
 * when `matchMedia` is unavailable.
 */
export function usePointerCoarse(): boolean {
  return useMediaQuery('(pointer: coarse)', false);
}

/** Screen orientation reported by {@link useOrientation}. */
export type Orientation = 'portrait' | 'landscape';

/**
 * Report the screen orientation. Falls back to `portrait` when `matchMedia` is
 * unavailable.
 */
export function useOrientation(): Orientation {
  const portrait = useMediaQuery('(orientation: portrait)', true);
  return portrait ? 'portrait' : 'landscape';
}
