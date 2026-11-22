import { navigate } from '../../app/router';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { SystemNotice } from './SystemNotice';

/**
 * Fixed page for an unregistered route; no data is fetched or displayed.
 *
 * The `page` utility supplies safe-area-aware logical padding and the centred
 * reading measure at every width and direction (WEB-07, UX-01). The inline
 * styles only centre this data-free notice and let long English and Arabic
 * labels wrap without a horizontal scroll: `responsive.css` has no dedicated
 * notice-panel class, so these stay logical and never name a physical side.
 * `SystemNotice` still owns the translated text, roles and single action, so
 * behaviour, API calls and exported names are unchanged.
 *
 * @returns The responsive, data-free not-found notice.
 */
export function NotFoundPage() {
  const breakpoint = useBreakpoint();
  return (
    <div
      className="page"
      data-breakpoint={breakpoint}
      style={{
        display: 'grid',
        placeItems: 'center',
        maxInlineSize: '44rem',
        marginInline: 'auto',
        textAlign: 'start',
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <SystemNotice
        titleKey="notFoundTitle"
        detailKey="notFoundDetail"
        actionKey="goHome"
        onAction={() => navigate('/')}
      />
    </div>
  );
}
