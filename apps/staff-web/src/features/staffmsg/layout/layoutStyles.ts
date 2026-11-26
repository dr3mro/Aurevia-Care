/**
 * Presentation-only layout rules for messenger and mail panes (WEB-07, UX-01).
 *
 * The breakpoints come from the shared breakpoint contract, so this module does
 * not define a second set of width thresholds. Every grid track is fractional,
 * every pane may shrink to zero and long values wrap: a narrow screen must not
 * acquire a horizontal scrollbar because one clinical string is long. The
 * composer stays a direct child of the wrapper and is sticky, so the viewport
 * is its scrollport and an on-screen keyboard cannot push it out of reach.
 */

import type { CSSProperties } from 'react';
import type { Breakpoint } from '../../../theme/breakpoints';

/** Number of simultaneously visible panes at a breakpoint. */
export type LayoutShape = 'single' | 'paired' | 'triple';

/** Grid coordinates of one pane. */
export interface PanePlacement {
  /** Grid column, counted from the inline-start edge (mirrored under RTL). */
  gridColumn: string;
  /** Grid row inside the wrapper. */
  gridRow: string;
}

/** Style sets applied to the wrapper, its panes and the docked composer. */
export interface PaneLayout {
  /** Pane count exposed for tests and assistive labels. */
  shape: LayoutShape;
  /** Grid container styles, constrained to the viewport width. */
  root: CSSProperties;
  /** Styles shared by each pane so long content wraps and stays reachable. */
  pane: CSSProperties;
  /** Composer styles that keep it reachable above the keyboard. */
  composer: CSSProperties;
  /** Grid coordinates that keep detail above composer in fewer columns. */
  placement: {
    list: PanePlacement;
    detail: PanePlacement;
    composer: PanePlacement;
  };
}

/** Pane counts, grid tracks and coordinates keyed by the shared breakpoints. */
const GRIDS: Record<
  Breakpoint,
  {
    shape: LayoutShape;
    columns: string;
    rows: string;
    placement: PaneLayout['placement'];
  }
> = {
  compact: {
    shape: 'single',
    columns: 'minmax(0, 1fr)',
    rows: 'auto auto',
    placement: {
      list: { gridColumn: '1', gridRow: '1 / span 2' },
      detail: { gridColumn: '1', gridRow: '1' },
      composer: { gridColumn: '1', gridRow: '2' },
    },
  },
  medium: {
    shape: 'paired',
    columns: 'minmax(0, 1fr) minmax(0, 2fr)',
    rows: 'auto auto',
    placement: {
      list: { gridColumn: '1', gridRow: '1 / span 2' },
      detail: { gridColumn: '2', gridRow: '1' },
      composer: { gridColumn: '2', gridRow: '2' },
    },
  },
  expanded: {
    shape: 'triple',
    columns: 'minmax(0, 1fr) minmax(0, 2fr) minmax(0, 1fr)',
    rows: 'auto',
    placement: {
      list: { gridColumn: '1', gridRow: '1' },
      detail: { gridColumn: '2', gridRow: '1' },
      composer: { gridColumn: '3', gridRow: '1' },
    },
  },
};

/**
 * Resolve the pane geometry for one responsive band.
 *
 * @param breakpoint - Band reported by the shared breakpoint hook.
 * @returns Style sets for the wrapper, panes, keyboard-safe composer and grid
 * placement. No style clips content; long text wraps inside its own pane.
 */
export function paneLayout(breakpoint: Breakpoint): PaneLayout {
  const { shape, columns, rows, placement } = GRIDS[breakpoint];
  return {
    shape,
    root: {
      display: 'grid',
      gap: 'var(--sc-space-4, 16px)',
      gridTemplateColumns: columns,
      gridTemplateRows: rows,
      inlineSize: '100%',
      maxInlineSize: '100%',
      // Reserve space for the sticky composer when this wrapper is the scroller.
      scrollPaddingBlockEnd: 'var(--sc-space-8, 32px)',
    },
    pane: {
      minInlineSize: 0,
      maxInlineSize: '100%',
      overflowWrap: 'anywhere',
    },
    // The shell maps the physical env() name to this logical variable once.
    // max() keeps the normal gap on devices without a safe-area inset.
    composer: {
      position: 'sticky',
      insetBlockEnd: 0,
      paddingBlock: 'var(--sc-space-3, 12px)',
      paddingInline: 'var(--sc-space-4, 16px)',
      paddingBlockEnd: 'max(var(--sc-space-3, 12px), var(--safe-block-end, 0px))',
      background: 'var(--sc-color-surface-default, #ffffff)',
    },
    placement,
  };
}
