/** Presentation styles for the responsive message layout (WEB-07, WEB-09). */

import type { CSSProperties } from 'react';
import type { ThemeTokens } from '../../../theme/tokens';

/** The five styled regions of a message layout. */
export interface PaneStyles {
  readonly root: CSSProperties;
  readonly panes: CSSProperties;
  readonly scrim: CSSProperties;
  readonly drawer: CSSProperties;
  readonly composer: CSSProperties;
}

/**
 * Resolve the layout styles from theme tokens.
 *
 * The root is a bounded scroll container (`max-block-size: 100dvh` with block
 * overflow): the panes scroll inside it while the sticky composer stays in
 * view. Scroll padding belongs on that same scroller so a focused composer
 * field is not covered when the on-screen keyboard shortens the viewport.
 * `overflow-x: clip` keeps a long value from forcing horizontal scroll.
 *
 * @param tokens - Active light/dark theme tokens.
 * @param gridTemplateColumns - Breakpoint-specific pane tracks, all `minmax(0, …)`.
 * @returns Style objects for the root, panes, scrim, drawer and composer.
 */
export function paneStyles(tokens: ThemeTokens, gridTemplateColumns: string): PaneStyles {
  return {
    root: {
      display: 'grid',
      gap: tokens.spacing.lg,
      inlineSize: '100%',
      maxInlineSize: '100%',
      minInlineSize: 0,
      maxBlockSize: '100dvh',
      overflowBlock: 'auto',
      overflowX: 'clip',
      boxSizing: 'border-box',
      scrollPaddingBlockEnd: tokens.spacing.lg,
    },
    panes: {
      display: 'grid',
      gap: tokens.spacing.lg,
      gridTemplateColumns,
      inlineSize: '100%',
      minInlineSize: 0,
    },
    scrim: {
      position: 'fixed',
      inset: 0,
      border: 0,
      background: 'rgba(0, 0, 0, 0.4)',
      zIndex: 9,
    },
    drawer: {
      position: 'fixed',
      insetBlock: 0,
      insetInlineStart: 0,
      inlineSize: 'min(20rem, 85vw)',
      padding: tokens.spacing.lg,
      background: tokens.background,
      borderInlineEnd: `1px solid ${tokens.border}`,
      overflow: 'auto',
      zIndex: 10,
    },
    composer: {
      position: 'sticky',
      insetBlockEnd: '0px',
      scrollMarginBlockEnd: tokens.spacing.lg,
      paddingBlock: tokens.spacing.md,
      paddingInline: tokens.spacing.lg,
      background: tokens.background,
      borderBlockStart: `1px solid ${tokens.border}`,
    },
  };
}
