/**
 * Pure layout decisions for the shared messaging panes (WEB-07, WEB-09).
 *
 * Width is a presentation input only: it never changes authorization, data or
 * the available actions. Every grid track is fractional with a zero minimum so
 * a long clinical value wraps instead of forcing horizontal scroll at 768 px.
 */

import type { Breakpoint } from '../../../theme/breakpoints';

/** Visible pane on a narrow screen: the list or the opened detail. */
export type MessagingStage = 'list' | 'detail';

/** Identifier of one pane region. */
export type PaneId = 'list' | 'detail' | 'auxiliary';

/** Which pane regions are visible at one breakpoint and compact stage. */
export interface PaneVisibility {
  readonly list: boolean;
  readonly detail: boolean;
  readonly auxiliary: boolean;
}

const ONE_TRACK = 'minmax(0, 1fr)';
const TWO_TRACKS = `${ONE_TRACK} ${ONE_TRACK}`;
const THREE_TRACKS = `${TWO_TRACKS} ${ONE_TRACK}`;

/**
 * Grid tracks for one breakpoint.
 *
 * @param breakpoint - Current presentation class from `useBreakpoint`.
 * @param hasAuxiliary - Whether the caller supplied an optional third pane.
 * @returns A space-separated track list containing only fractional tracks.
 */
export function columnsFor(breakpoint: Breakpoint, hasAuxiliary: boolean): string {
  if (breakpoint === 'compact') {
    return ONE_TRACK;
  }
  if (breakpoint === 'medium') {
    return TWO_TRACKS;
  }
  return hasAuxiliary ? THREE_TRACKS : TWO_TRACKS;
}

/**
 * Resolve the visible panes for a breakpoint and compact stage.
 *
 * The auxiliary pane is rendered only at expanded; at medium the caller is
 * expected to compose its content into the list pane so no action is lost.
 *
 * @param breakpoint - Current presentation class.
 * @param stage - Which pane a narrow screen is showing.
 * @returns Visibility for each pane region.
 */
export function paneVisibility(breakpoint: Breakpoint, stage: MessagingStage): PaneVisibility {
  if (breakpoint === 'expanded') {
    return { list: true, detail: true, auxiliary: true };
  }
  if (breakpoint === 'medium') {
    return { list: true, detail: true, auxiliary: false };
  }
  if (stage === 'list') {
    return { list: true, detail: false, auxiliary: false };
  }
  return { list: false, detail: true, auxiliary: false };
}
