import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Button } from '../../../components/Button';
import { defined } from '../../../components/invariant';
import { useI18n } from '../../../i18n/I18nProvider';
import { BREAKPOINT_WIDTHS } from '../../../theme/breakpoints';
import type { Breakpoint } from '../../../theme/breakpoints';
import { useTheme } from '../../../theme/ThemeProvider';
import { useBreakpoint } from '../../../theme/useBreakpoint';
import { layoutText, PANE_LABEL_KEYS } from './labels';
import { layoutFor } from './panes';
import type { PaneName } from './panes';
import { paneStyles } from './styles';

/** Props for the responsive messenger and mail layout wrapper. */
export interface MessagePaneLayoutProps {
  /** Explicit viewport width for deterministic layouts; the hook is used when omitted. */
  readonly width?: number;
  /** Whether the owner has opened a detail record; drives the compact single pane. */
  readonly hasDetail: boolean;
  /** Requests that the owner close the detail; selection and typed work stay owned by them. */
  readonly onBack: () => void;
  /** Secondary navigation pane (folders or conversation groups). */
  readonly auxiliary: ReactNode;
  /** Primary list pane (conversations or messages). */
  readonly list: ReactNode;
  /** Reading pane (thread or message detail). */
  readonly detail: ReactNode;
  /** Composer content, kept in a sticky region above the on-screen keyboard. */
  readonly composer: ReactNode;
}

/** Representative widths used when the viewport hook supplies the breakpoint. */
const FALLBACK_WIDTH: Record<Breakpoint, number> = {
  compact: 0,
  medium: BREAKPOINT_WIDTHS.medium,
  expanded: BREAKPOINT_WIDTHS.expanded,
};

/**
 * Compose the responsive panes, drawer, Back action and sticky composer for
 * staff messaging surfaces. The wrapper owns presentation and focus movement
 * only: all durable selection and draft state stays with the caller, so a
 * resize or a Back action cannot discard typed work (WEB-09).
 */
export function MessagePaneLayout(props: MessagePaneLayoutProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const viewportBreakpoint = useBreakpoint();
  const width = props.width ?? FALLBACK_WIDTH[viewportBreakpoint];
  const layout = layoutFor(width, props.hasDetail);
  // The open flag is bound to the layout that opened it. A resize or a Back
  // action changes that key; clear the stored key during that render so the
  // drawer cannot reopen when the old width returns, and so no focus-moving
  // effect runs merely because a resize crossed a breakpoint.
  const drawerKey = `${layout.breakpoint}:${props.hasDetail ? 'detail' : 'list'}`;
  const [openDrawerKey, setOpenDrawerKey] = useState<string | undefined>(undefined);
  const [lastDrawerKey, setLastDrawerKey] = useState(drawerKey);
  if (lastDrawerKey !== drawerKey) {
    setLastDrawerKey(drawerKey);
    setOpenDrawerKey(undefined);
  }
  const drawerOpen = openDrawerKey === drawerKey && layout.drawerPanes.length > 0;
  const toggleRef = useRef<HTMLButtonElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLElement>(null);
  const previousDetail = useRef(props.hasDetail);
  const styles = paneStyles(tokens, layout.gridTemplateColumns);

  const closeDrawer = useCallback((): void => {
    setOpenDrawerKey(undefined);
    defined(toggleRef.current, 'drawer toggle').focus();
  }, []);

  // Opening the drawer moves focus to its safe control; closing it returns
  // focus to the toggle so keyboard users never land on a hidden region.
  useEffect(() => {
    if (drawerOpen) {
      defined(closeRef.current, 'drawer close control').focus();
    }
  }, [drawerOpen]);

  // Escape is handled at the document so the listener works while focus is in
  // any control inside the drawer, not only on the drawer container itself.
  useEffect(() => {
    if (!drawerOpen) {
      return undefined;
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        closeDrawer();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [drawerOpen, closeDrawer]);

  // Back swaps the compact list back into the grid after the owner clears the
  // detail. Focusing the restored region gives keyboard users their place back.
  useEffect(() => {
    if (previousDetail.current && !props.hasDetail) {
      defined(listRef.current, 'restored list pane').focus();
    }
    previousDetail.current = props.hasDetail;
  }, [props.hasDetail]);

  const contents: Record<PaneName, ReactNode> = {
    auxiliary: props.auxiliary,
    list: props.list,
    detail: props.detail,
  };

  /** Render one pane exactly once, in the grid or in the drawer. */
  function pane(name: PaneName): ReactNode {
    return (
      <section
        key={name}
        ref={name === 'list' ? listRef : undefined}
        aria-label={layoutText(locale, PANE_LABEL_KEYS[name])}
        data-pane={name}
        tabIndex={-1}
        style={{ minInlineSize: 0, overflowWrap: 'anywhere' }}
      >
        {contents[name]}
      </section>
    );
  }

  return (
    <section
      className="page"
      data-breakpoint={layout.breakpoint}
      data-visible-panes={layout.visiblePanes.join(' ')}
      data-drawer-open={drawerOpen ? 'true' : 'false'}
      aria-label={layoutText(locale, 'layoutLabel')}
      style={styles.root}
    >
      <div data-message-panes="" style={styles.panes}>
        {layout.visiblePanes.map(pane)}
      </div>
      {/* Keep the toggle mounted so focus return never dereferences a stale
          ref when a breakpoint change removes the drawer. */}
      <div hidden={!layout.showDrawerToggle}>
        <Button variant="secondary" buttonRef={toggleRef} onClick={() => setOpenDrawerKey(drawerKey)}>
          {layoutText(locale, 'drawerToggle')}
        </Button>
      </div>
      {layout.drawerPanes.length > 0 ? (
        <>
          <button
            type="button"
            data-message-scrim=""
            aria-label={layoutText(locale, 'scrimClose')}
            hidden={!drawerOpen}
            onClick={closeDrawer}
            style={styles.scrim}
          />
          <div
            data-message-drawer=""
            role="complementary"
            aria-label={layoutText(locale, 'drawerToggle')}
            tabIndex={-1}
            hidden={!drawerOpen}
            style={styles.drawer}
          >
            <Button variant="secondary" buttonRef={closeRef} onClick={closeDrawer}>
              {layoutText(locale, 'drawerClose')}
            </Button>
            {layout.drawerPanes.map(pane)}
          </div>
        </>
      ) : null}
      {layout.showBack ? (
        <Button variant="secondary" onClick={props.onBack}>
          {layoutText(locale, 'back')}
        </Button>
      ) : null}
      <section
        data-testid="message-layout-composer"
        aria-label={layoutText(locale, 'composerPane')}
        style={styles.composer}
      >
        {props.composer}
      </section>
    </section>
  );
}
