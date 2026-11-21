/**
 * Responsive layout classes for the staff web. These describe presentation only
 * and never change what the server authorizes (WEB-07, UX-01).
 */
export type Breakpoint = 'compact' | 'medium' | 'expanded';

/** Minimum viewport width in CSS pixels for each non-compact breakpoint. */
export const BREAKPOINT_WIDTHS = {
  medium: 768,
  expanded: 1280,
} as const;

/** `min-width` media queries matching the medium and expanded breakpoints. */
export const BREAKPOINT_MEDIA_QUERIES = {
  medium: `(min-width: ${BREAKPOINT_WIDTHS.medium}px)`,
  expanded: `(min-width: ${BREAKPOINT_WIDTHS.expanded}px)`,
} as const;

/**
 * Resolve the layout class for a viewport width. Compact is the absence of the
 * two `min-width` breakpoints, so it has no query of its own.
 *
 * @param width - Viewport width in CSS pixels.
 * @returns `expanded` at 1280 and above, `medium` at 768 to 1279, otherwise `compact`.
 */
export function breakpointFor(width: number): Breakpoint {
  if (width >= BREAKPOINT_WIDTHS.expanded) {
    return 'expanded';
  }
  if (width >= BREAKPOINT_WIDTHS.medium) {
    return 'medium';
  }
  return 'compact';
}
