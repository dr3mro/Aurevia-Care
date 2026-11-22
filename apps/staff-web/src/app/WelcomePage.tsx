import { EmptyState } from '../components/StateViews';
import { useI18n } from '../i18n/I18nProvider';
import { useBreakpoint } from '../theme/useBreakpoint';
import { shellText } from './shellText';

/**
 * Placeholder home route until the role-aware worklists are wired in.
 *
 * The `page` utility supplies safe-area-aware logical padding, a centred
 * reading measure and a single shrinkable column. The inline styles only add
 * what no dedicated class provides: shrink-to-fit, wrapping of long English and
 * Arabic labels, and a direction-neutral text start (WEB-07, UX-01).
 * No field or action is hidden at any breakpoint; this route has no inputs, so
 * the shared control components own the 16px text and coarse-pointer sizing.
 *
 * @returns The labelled welcome heading and the polite empty state.
 */
export function WelcomePage() {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  return (
    <section
      aria-labelledby="welcome-title"
      className="page"
      data-breakpoint={breakpoint}
      style={{
        minInlineSize: 0,
        overflowWrap: 'anywhere',
        textAlign: 'start',
      }}
    >
      <h2 id="welcome-title">{shellText(locale, 'welcome')}</h2>
      <EmptyState />
    </section>
  );
}
