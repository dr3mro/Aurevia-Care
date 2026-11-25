/**
 * Pane resolution for the staff messenger and mail layouts (WEB-07, WEB-09).
 *
 * Width is a presentation input only: it never changes what the server
 * authorizes or which data the caller renders. Every pane stays in the tree;
 * panes that do not fit are offered through the accessible drawer instead of
 * being hidden with `display: none`, so no action disappears at a breakpoint.
 */

import { breakpointFor } from '../../../theme/breakpoints';
import type { Breakpoint } from '../../../theme/breakpoints';

/** The three content regions shared by messenger and mail. */
export type PaneName = 'auxiliary' | 'list' | 'detail';

/** Resolved presentation for one viewport width and selection state. */
export interface PaneLayout {
  readonly breakpoint: Breakpoint;
  readonly visiblePanes: readonly PaneName[];
  readonly drawerPanes: readonly PaneName[];
  readonly gridTemplateColumns: string;
  readonly showBack: boolean;
  readonly showDrawerToggle: boolean;
}

/**
 * Grid tracks use `minmax(0, …)` throughout: a long synthetic value can wrap
 * instead of forcing the grid wider than the viewport at 768 px (WCAG 1.4.10).
 */
const GRID_COLUMNS: Record<Breakpoint, string> = {
  compact: 'minmax(0, 1fr)',
  medium: 'minmax(0, 1fr) minmax(0, 1fr)',
  expanded: 'minmax(0, 16rem) minmax(0, 1fr) minmax(0, 1.25fr)',
};

/** Panes placed in the main grid at one breakpoint. */
function visiblePanesFor(breakpoint: Breakpoint, hasDetail: boolean): readonly PaneName[] {
  if (breakpoint === 'compact') {
    return hasDetail ? ['detail'] : ['list'];
  }
  if (breakpoint === 'medium') {
    return ['list', 'detail'];
  }
  return ['auxiliary', 'list', 'detail'];
}

/** Panes that remain reachable through the drawer at one breakpoint. */
function drawerPanesFor(breakpoint: Breakpoint, hasDetail: boolean): readonly PaneName[] {
  if (breakpoint === 'compact') {
    return hasDetail ? ['auxiliary', 'list'] : ['auxiliary'];
  }
  if (breakpoint === 'medium') {
    return ['auxiliary'];
  }
  return [];
}

/**
 * Resolve the pane presentation for a viewport width.
 *
 * @param width - Viewport inline size in CSS pixels.
 * @param hasDetail - Whether the owner has opened a detail record. On compact
 * screens the detail replaces the list, with a Back action to return to it.
 * @returns The breakpoint, visible and drawer panes, grid tracks and controls.
 */
export function layoutFor(width: number, hasDetail: boolean): PaneLayout {
  const breakpoint = breakpointFor(width);
  return {
    breakpoint,
    visiblePanes: visiblePanesFor(breakpoint, hasDetail),
    drawerPanes: drawerPanesFor(breakpoint, hasDetail),
    gridTemplateColumns: GRID_COLUMNS[breakpoint],
    showBack: breakpoint === 'compact' && hasDetail,
    showDrawerToggle: breakpoint !== 'expanded',
  };
}
