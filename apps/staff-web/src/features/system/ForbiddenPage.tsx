import { navigate } from '../../app/router';
import { Button } from '../../components/Button';
import { ForbiddenState } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { systemText } from './messages';

/**
 * Fixed page for a role with no access; it explains without revealing data.
 *
 * The shared forbidden state view owns the centred, safe-area-aware alert
 * panel. This page supplies its one action in a row of the same reading width:
 * stacked and full inline size on phones, inline from the medium band up.
 * Width changes layout only — no text or action is added or removed.
 */
export function ForbiddenPage() {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const goHome = systemText(locale, 'goHome');
  return (
    <>
      <ForbiddenState />
      <div
        className="page"
        data-breakpoint={breakpoint}
        style={{
          // Match the state panel's reading measure so the action lines up with it.
          maxInlineSize: '44rem',
          minInlineSize: 0,
          overflowWrap: 'anywhere',
          textAlign: 'start',
        }}
      >
        <div
          className={breakpoint === 'compact' ? 'stack' : 'cluster'}
          data-breakpoint={breakpoint}
          style={{ inlineSize: '100%', minInlineSize: 0 }}
        >
          {/* The stylesheet's .touch rule owns both target floors (24px fine, 44px coarse). */}
          <div className="touch" style={{ display: 'grid' }}>
            <Button onClick={() => navigate('/')}>{goHome}</Button>
          </div>
        </div>
      </div>
    </>
  );
}
