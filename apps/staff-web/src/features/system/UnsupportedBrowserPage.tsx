import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { SystemNotice } from './SystemNotice';

/**
 * Perform a document navigation so the client-support check is evaluated again
 * on a real load, for example after a viewport resize. The location is
 * injectable so this behavior can be asserted without a real navigation.
 */
export function assignHomeLocation(location: Pick<Location, 'assign'>): void {
  location.assign('/');
}

/**
 * Plain message shown instead of the whole application when the client fails
 * the support check. The entry point supplies the providers; this page renders
 * no clinical data, navigation or shell chrome (WEB-07). Its action performs a
 * document load rather than SPA navigation because no router is mounted in the
 * unsupported tree.
 *
 * The `page` utility owns the safe-area-aware logical padding, the single
 * column and the centred reading measure; the inline styles only centre the
 * notice and keep long English and Arabic text wrappable without a horizontal
 * scroll (WEB-07, UX-01). No dedicated state-panel class exists in the shared
 * stylesheet, so layout uses those classes plus logical inline styles rather
 * than a new rule. `data-breakpoint` makes the layout band observable to tests
 * without asking jsdom to measure pixels.
 */
export function UnsupportedBrowserPage() {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  return (
    <div
      className="page"
      data-breakpoint={breakpoint}
      style={{
        display: 'grid',
        placeItems: 'center',
        inlineSize: '100%',
        maxInlineSize: '44rem',
        marginInline: 'auto',
        minInlineSize: 0,
        overflowWrap: 'anywhere',
        textAlign: 'start',
        background: tokens.background,
        color: tokens.foreground,
        border: `1px solid ${tokens.border}`,
      }}
    >
      <div
        style={{
          display: 'grid',
          gap: tokens.spacing.lg,
          inlineSize: '100%',
          minInlineSize: 0,
          overflowWrap: 'anywhere',
          textAlign: 'start',
        }}
      >
        <SystemNotice
          titleKey="unsupportedTitle"
          detailKey="unsupportedDetail"
          actionKey="goHome"
          onAction={() => assignHomeLocation(window.location)}
        />
      </div>
    </div>
  );
}
