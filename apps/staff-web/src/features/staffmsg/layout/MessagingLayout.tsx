import { createContext, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Button } from '../../../components/Button';
import type { Locale } from '../../../i18n/messages';
import { useTheme } from '../../../theme/ThemeProvider';
import type { ThemeTokens } from '../../../theme/tokens';
import { useBreakpoint } from '../../../theme/useBreakpoint';
import { layoutText } from './layoutText';
import { columnsFor, paneVisibility } from './layoutModel';
import type { MessagingStage, PaneId } from './layoutModel';

/** One labelled pane supplied by the owning messenger or mail screen. */
export interface MessagingPane {
  /** Accessible name of the pane region. */
  readonly label: string;
  /** Rendered content; stays mounted when the pane is hidden on a narrow screen. */
  readonly content: ReactNode;
}

/** Props for the responsive messaging layout wrapper. */
export interface MessagingLayoutProps {
  /** Accessible title of the messaging surface. */
  readonly title: string;
  /** Locale used for the wrapper's own labels. */
  readonly locale: Locale;
  /** Primary list pane (conversations, or folders and message list composed together). */
  readonly list: MessagingPane;
  /** Detail pane (thread, or reading pane). */
  readonly detail: MessagingPane;
  /** Optional third pane shown only at the expanded breakpoint. */
  readonly auxiliary?: MessagingPane | undefined;
  /** Notices such as the emergency banner or connection status, placed above the panes. */
  readonly notices?: ReactNode;
  /** Composer rendered as a sticky footer that stays reachable above the keyboard. */
  readonly composer: ReactNode;
}

/** Compact navigation exposed to list rows through {@link useMessagingNavigation}. */
export interface MessagingNavigation {
  /** Pane currently shown on a narrow screen. */
  readonly stage: MessagingStage;
  /** Show the detail pane after a row is selected. */
  readonly openDetail: () => void;
  /** Return to the list pane from the detail. */
  readonly back: () => void;
}

const NavigationContext = createContext<MessagingNavigation | undefined>(undefined);

/**
 * Read compact navigation from the nearest layout. Fails closed when no wrapper
 * is mounted so a row cannot silently lose its back action.
 */
export function useMessagingNavigation(): MessagingNavigation {
  const navigation = useContext(NavigationContext);
  if (navigation === undefined) {
    throw new Error('useMessagingNavigation must be used inside MessagingLayout');
  }
  return navigation;
}

/** A labelled pane region. No display declaration is set here: `hidden` must win. */
function Pane({
  id,
  pane,
  visible,
  tokens,
}: {
  readonly id: PaneId;
  readonly pane: MessagingPane;
  readonly visible: boolean;
  readonly tokens: ThemeTokens;
}) {
  return (
    <section
      aria-label={pane.label}
      data-pane={id}
      hidden={!visible}
      style={{ minInlineSize: 0, overflowWrap: 'anywhere' }}
    >
      <div className="stack" style={{ gap: tokens.spacing.md }}>
        {pane.content}
      </div>
    </section>
  );
}

/** The narrow-screen back bar; an explicit button, never a gesture-only action. */
function BackBar({ label, onBack, tokens }: { readonly label: string; readonly onBack: () => void; readonly tokens: ThemeTokens }) {
  return (
    <div className="cluster" data-back-bar style={{ paddingBlock: tokens.spacing.sm }}>
      <Button onClick={onBack}>{label}</Button>
    </div>
  );
}

/**
 * Compose a three-pane desktop, two-pane tablet or single-pane phone layout for
 * messenger and mail (WEB-07, WEB-09). The root is the scroll container: its
 * logical inline overflow is clipped so a wide value wraps instead of creating
 * horizontal scroll, and its block scroll padding reserves room for the sticky
 * composer. Panes keep stable tree positions and are toggled with `hidden`, so
 * typed work, selection and focus survive an orientation change. Dialogs stay
 * with the owning screen because they are not part of the pane flow.
 */
export function MessagingLayout({
  title,
  locale,
  list,
  detail,
  auxiliary,
  notices,
  composer,
}: MessagingLayoutProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const [stage, setStage] = useState<MessagingStage>('list');
  const visibility = paneVisibility(breakpoint, stage);
  const navigation = useMemo<MessagingNavigation>(
    () => ({
      stage,
      openDetail: () => setStage('detail'),
      back: () => setStage('list'),
    }),
    [stage],
  );
  const showBack = breakpoint === 'compact' && stage === 'detail';
  return (
    <NavigationContext.Provider value={navigation}>
      <section
        className="page"
        data-layout-scroll="true"
        data-breakpoint={breakpoint}
        data-stage={stage}
        aria-label={title}
        style={{
          // .page sets display:grid. A sticky item is confined to its grid
          // area, so the scrollport must be a block container for the composer
          // to remain pinned above the keyboard while the panes scroll.
          display: 'block',
          overflowBlock: 'auto',
          overflowInline: 'clip',
          maxBlockSize: '100dvh',
          // 16rem is the layout-boundary reserve for the composer; space.2
          // keeps a focused field clear of the sticky region above the keyboard.
          scrollPaddingBlockEnd: `calc(16rem + ${tokens.spacing.sm})`,
        }}
      >
        <h2>{title}</h2>
        {notices}
        {showBack ? (
          <BackBar label={layoutText(locale, 'back')} onBack={navigation.back} tokens={tokens} />
        ) : null}
        <div
          className="grid-2"
          role="group"
          aria-label={layoutText(locale, 'panes')}
          data-panes="true"
          style={{ gridTemplateColumns: columnsFor(breakpoint, auxiliary !== undefined) }}
        >
          <Pane id="list" pane={list} visible={visibility.list} tokens={tokens} />
          <Pane id="detail" pane={detail} visible={visibility.detail} tokens={tokens} />
          {visibility.auxiliary && auxiliary !== undefined ? (
            <Pane id="auxiliary" pane={auxiliary} visible={visibility.auxiliary} tokens={tokens} />
          ) : null}
        </div>
        <section
          className="stack"
          aria-label={layoutText(locale, 'composer')}
          data-sticky-composer="true"
          style={{
            position: 'sticky',
            insetBlockEnd: 0,
            gap: tokens.spacing.md,
            paddingBlock: tokens.spacing.md,
            paddingInline: tokens.spacing.md,
            background: tokens.background,
          }}
        >
          {composer}
        </section>
      </section>
    </NavigationContext.Provider>
  );
}
