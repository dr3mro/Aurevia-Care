import { useEffect, useState } from 'react';
import { BREAKPOINT_MEDIA_QUERIES } from './breakpoints';
import type { Breakpoint } from './breakpoints';

type ChangeListener = (event: MediaQueryListEvent) => void;
type MatchMedia = (query: string) => MediaQueryList;

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
  const [matches, setMatches] = useState<boolean>(() => {
    const media: MatchMedia | undefined = globalThis.matchMedia;
    const list = media?.(query);
    return list ? list.matches : fallback;
  });

  useEffect(() => {
    const media: MatchMedia | undefined = globalThis.matchMedia;
    const list = media?.(query);
    if (!list) {
      return;
    }
    const onChange: ChangeListener = (event) => setMatches(event.matches);
    list.addEventListener('change', onChange);
    setMatches(list.matches);
    return () => list.removeEventListener('change', onChange);
  }, [query]);

  return matches;
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
