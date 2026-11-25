import type { CSSProperties, ReactNode } from 'react';
import { Button } from '../../../components/Button';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import { useBreakpoint } from '../../../theme/useBreakpoint';
import { layoutText } from './labels';

/**
 * Props for the shared pane layout of the instant messenger and staff mail.
 * The parent owns the selection: `detailOpen` says whether a conversation or
 * message is selected, and `onBack` clears that selection. Keeping the state
 * with the parent means a resize never discards typed work or changes which
 * record is selected (WEB-09).
 */
export interface StaffMessageLayoutProps {
  /** The conversation or folder list pane. */
  readonly list: ReactNode;
  /** The selected thread or reading pane. */
  readonly detail: ReactNode;
  /** The composer; rendered only with a detail pane on narrow screens. */
  readonly composer: ReactNode;
  /** Whether the parent currently has a selected conversation or message. */
  readonly detailOpen: boolean;
  /** Clear the selection so the compact layout returns to the list. */
  readonly onBack: () => void;
}

/** Grid and layout name for each responsive band (WEB-07, UX-01). */
const BAND_VIEW = {
  compact: { columns: 'minmax(0, 1fr)', layout: 'single-pane' },
  medium: { columns: 'repeat(2, minmax(0, 1fr))', layout: 'two-pane' },
  expanded: { columns: 'repeat(3, minmax(0, 1fr))', layout: 'three-pane' },
} as const;

/**
 * Presentational pane wrapper shared by messenger and mail. Desktop shows the
 * list, detail and composer side by side; tablet shows the two reading panes
 * with the composer as a full-width footer; a narrow screen shows one pane at a
 * time and offers a labelled Back action to restore the list.
 *
 * Every track is `minmax(0, 1fr)` and every pane may shrink, so a long clinical
 * value wraps rather than forcing horizontal scroll at 768 px. Side-aware
 * styles are logical properties only, so one DOM mirrors under right-to-left.
 * The footer is sticky with a logical scroll-space reservation: an on-screen
 * keyboard shrinks the viewport and the composer must stay reachable without
 * dismissing it. jsdom can assert these attributes but cannot paint a keyboard
 * or measure reflow; real-browser verification belongs to the E2E stories.
 */
export function StaffMessageLayout({
  list,
  detail,
  composer,
  detailOpen,
  onBack,
}: StaffMessageLayoutProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const band = BAND_VIEW[breakpoint];

  const paneStyle: CSSProperties = { minInlineSize: 0, overflowWrap: 'anywhere' };
  const gridStyle: CSSProperties = {
    display: 'grid',
    gap: tokens.spacing.lg,
    gridTemplateColumns: band.columns,
    minInlineSize: 0,
  };
  const stickyComposer = breakpoint !== 'expanded';
  const composerStyle: CSSProperties = stickyComposer
    ? {
        ...paneStyle,
        // The keyboard covers the bottom of the viewport; a logical sticky
        // inset keeps the composer above it in both writing directions.
        position: 'sticky',
        insetBlockEnd: 0,
        paddingBlock: 'var(--sc-space-2, 8px)',
        paddingInline: 'var(--sc-space-4, 16px)',
        background: 'var(--sc-color-surface-default, #ffffff)',
        scrollMarginBlockEnd: tokens.spacing.lg,
        zIndex: 1,
      }
    : paneStyle;

  const listPane = (
    <div key="list" data-pane="list" role="region" aria-label={layoutText(locale, 'listPane')} style={paneStyle}>
      {list}
    </div>
  );
  const detailPane = (
    <div key="detail" data-pane="detail" role="region" aria-label={layoutText(locale, 'detailPane')} style={paneStyle}>
      {detail}
    </div>
  );
  const composerPane = (
    <div
      key="composer"
      data-pane="composer"
      data-part="composer"
      role="region"
      aria-label={layoutText(locale, 'composerPane')}
      style={composerStyle}
    >
      {composer}
    </div>
  );

  const visible =
    breakpoint === 'expanded'
      ? [listPane, detailPane, composerPane]
      : breakpoint === 'medium'
        ? [listPane, detailPane]
        : detailOpen
          ? [detailPane]
          : [listPane];
  const showBack = breakpoint === 'compact' && detailOpen;
  const footer = breakpoint === 'medium' || showBack ? composerPane : null;

  return (
    <section
      className="page"
      data-breakpoint={breakpoint}
      data-layout={band.layout}
      style={{ scrollPaddingBlockEnd: 'var(--sc-space-8, 32px)' }}
    >
      {showBack ? <Button onClick={onBack}>{layoutText(locale, 'back')}</Button> : null}
      <div data-part="panes" style={gridStyle}>
        {visible}
      </div>
      {footer}
    </section>
  );
}
