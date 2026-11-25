import type { CSSProperties, ReactNode } from 'react';
import { useTheme } from '../../../theme/ThemeProvider';
import { useBreakpoint } from '../../../theme/useBreakpoint';

/**
 * Presentation inputs for the messenger and mail panes. The caller owns every
 * selection and command; this component only decides which panes are visible
 * at the current width, so width never changes authorized data or actions.
 */
export interface StaffMessageLayoutProps {
  /** Accessible name of the whole layout region. */
  label: string;
  /** Conversation or folder list, shown as the first pane. */
  list: ReactNode;
  /** Thread or reading pane, shown beside the list from the medium band up. */
  detail: ReactNode;
  /** Composer, docked where a virtual keyboard cannot cover its action. */
  composer: ReactNode;
  /** Whether a detail has been opened on a compact screen. */
  detailOpen: boolean;
  /** Accessible name of the compact back action that restores the list. */
  backLabel: string;
  /** Invoked by the compact back action; the caller clears its selection. */
  onBack: () => void;
}

/**
 * Lay out staff messages as three panes on desktop, two on tablet and one pane
 * plus a labelled back action on a phone. The composer stays in the same React
 * tree at every width and is sticky below the expanded band, with block-end
 * padding floored by the mapped safe-area inset so a focused field and its Send
 * action remain reachable while the on-screen keyboard is open. All tracks are
 * `minmax(0, …)`, and all spacing uses logical properties, so a long clinical
 * value wraps under both LTR and RTL instead of forcing horizontal scroll.
 *
 * @param props - Pane content, accessible labels and the caller's back command.
 * @returns A single labelled region whose structure follows the resolved band.
 */
export function StaffMessageLayout({
  label,
  list,
  detail,
  composer,
  detailOpen,
  backLabel,
  onBack,
}: StaffMessageLayoutProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const expanded = breakpoint === 'expanded';
  const compact = breakpoint === 'compact';

  const paneStyle: CSSProperties = { minInlineSize: 0, overflowWrap: 'anywhere' };
  const gridStyle: CSSProperties = {
    display: 'grid',
    gap: tokens.spacing.lg,
    minInlineSize: 0,
    overflowX: 'hidden',
    gridTemplateColumns: expanded
      ? 'minmax(0, 1fr) minmax(0, 1fr) minmax(0, 1fr)'
      : compact
        ? 'minmax(0, 1fr)'
        : 'minmax(0, 1fr) minmax(0, 1fr)',
  };
  // On a narrower screen the composer spans the content grid and is pinned to
  // the block end. The safe-area value is mapped once by the shell, so no
  // component names a physical inset or invents a new spacing token.
  const composerStyle: CSSProperties = expanded
    ? paneStyle
    : {
        ...paneStyle,
        gridColumn: '1 / -1',
        position: 'sticky',
        insetBlockEnd: 0,
        paddingBlockEnd: `max(${tokens.spacing.md}, var(--safe-block-end, 0px))`,
      };

  return (
    <section
      className="page"
      data-testid="staff-message-layout"
      data-breakpoint={breakpoint}
      aria-label={label}
      style={{ minInlineSize: 0, overflowX: 'hidden', overflowWrap: 'anywhere' }}
    >
      {compact && detailOpen ? (
        <button type="button" className="touch" onClick={onBack}>
          {backLabel}
        </button>
      ) : null}
      <div data-testid="staff-message-panes" style={gridStyle}>
        {!compact || !detailOpen ? (
          <div data-testid="staff-message-pane-list" style={paneStyle}>
            {list}
          </div>
        ) : null}
        {!compact || detailOpen ? (
          <div data-testid="staff-message-pane-detail" style={paneStyle}>
            {detail}
          </div>
        ) : null}
        <div data-testid="staff-message-pane-composer" style={composerStyle}>
          {composer}
        </div>
      </div>
    </section>
  );
}
