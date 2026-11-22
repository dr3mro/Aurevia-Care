import { navigate } from '../../app/router';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { SystemNotice } from './SystemNotice';

/**
 * Fixed page shown after sign out; it holds no patient data (WEB-03).
 *
 * The `page` utility owns the reading measure, the single-column grid and the
 * logical safe-area padding; the `stack` child lets the notice grow with its
 * text so a long English or Arabic string wraps instead of forcing a horizontal
 * scroll. The resolved layout class is exposed as `data-breakpoint` because
 * jsdom cannot evaluate the width media queries. Direction stays on the
 * document, so the same markup mirrors under right-to-left. Text, navigation
 * behavior and the exported name are unchanged.
 */
export function SignedOutPage() {
  const breakpoint = useBreakpoint();
  // Layout comes only from the shared classes: an inline declaration would
  // outrank a future responsive.css rule at one of the breakpoints.
  return (
    <div className="page" data-breakpoint={breakpoint}>
      <div className="stack">
        <SystemNotice
          titleKey="signedOutTitle"
          detailKey="signedOutDetail"
          actionKey="signIn"
          onAction={() => navigate('/')}
        />
      </div>
    </div>
  );
}
