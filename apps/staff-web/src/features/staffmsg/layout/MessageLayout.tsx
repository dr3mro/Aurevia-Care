/**
 * Shared pane wrapper for staff messenger and mail (WEB-07, UX-01).
 *
 * Desktop shows three panes, the 768 px tablet band shows a list beside a
 * grouped detail/composer column, and a narrow screen shows a single active
 * pane with an explicit back action. The wrapper owns no data and makes no
 * authorization decision: caller-owned nodes are placed into named regions and
 * the caller flips `detailOpen` when a row is chosen. Presentation never hides
 * a field or action; only its placement changes. Each pane is a direct grid
 * child so the sticky composer's scrollport is the viewport, not a short cell.
 */

import type { ReactElement, ReactNode } from 'react';
import { useI18n } from '../../../i18n/I18nProvider';
import { useBreakpoint } from '../../../theme/useBreakpoint';
import { layoutText } from './layoutMessages';
import { paneLayout } from './layoutStyles';

/** Caller-supplied content and navigation state for the pane wrapper. */
export interface MessageLayoutProps {
  /** First pane: conversations or mail folders and message list. */
  list: ReactNode;
  /** Second pane: the selected thread, message or draft. */
  detail: ReactNode;
  /** Composer kept reachable while an on-screen keyboard is open. */
  composer: ReactNode;
  /** Whether the detail view is active on a single-pane screen. */
  detailOpen: boolean;
  /** Return from the detail view to the list on a single-pane screen. */
  onBack: () => void;
}

/**
 * Arrange message panes for the current viewport and keep the list reachable.
 *
 * @param props - Caller-owned panes, selection state and back handler.
 * @returns A labelled grid whose pane count follows the breakpoint.
 */
export function MessageLayout(props: MessageLayoutProps): ReactElement {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const layout = paneLayout(breakpoint);
  const single = breakpoint === 'compact';
  const showList = !single || !props.detailOpen;
  const showDetail = !single || props.detailOpen;
  const showBack = single && props.detailOpen;

  const listPane = showList ? (
    <section
      aria-label={layoutText(locale, 'listPane')}
      style={{ ...layout.pane, ...layout.placement.list }}
      data-pane="list"
    >
      {props.list}
    </section>
  ) : null;
  const detailPane = showDetail ? (
    <section
      aria-label={layoutText(locale, 'detailPane')}
      style={{ ...layout.pane, ...layout.placement.detail }}
      data-pane="detail"
    >
      {showBack ? (
        <button
          type="button"
          className="touch"
          data-testid="message-layout-back"
          onClick={props.onBack}
        >
          {layoutText(locale, 'backToList')}
        </button>
      ) : null}
      {props.detail}
    </section>
  ) : null;
  const composerPane = showDetail ? (
    <section
      aria-label={layoutText(locale, 'composerPane')}
      style={{ ...layout.pane, ...layout.composer, ...layout.placement.composer }}
      data-pane="composer"
    >
      {props.composer}
    </section>
  ) : null;

  return (
    <section
      className="page"
      data-breakpoint={breakpoint}
      data-panes={layout.shape}
      aria-label={layoutText(locale, 'layout')}
      style={layout.root}
    >
      {listPane}
      {detailPane}
      {composerPane}
    </section>
  );
}
