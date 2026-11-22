import { EmptyState } from '../components/StateViews';
import { useI18n } from '../i18n/I18nProvider';
import { useBreakpoint } from '../theme/useBreakpoint';
import { shellText } from './shellText';

/**
 * Catch-all route: a plain, data-free page for unregistered paths.
 *
 * The `page` utility supplies the safe-area-aware logical padding and the
 * centred reading measure; `stack` keeps the heading above the state panel
 * with no fixed block size, so a long English or Arabic title wraps instead of
 * forcing a horizontal scroll at 320 px or at 200 percent text zoom. The inline
 * styles only keep both children shrinkable and start-aligned (there is no
 * dedicated page-title class in `responsive.css`); every property is logical,
 * so the same markup mirrors under right-to-left. Behaviour, text and exported
 * names are unchanged (WEB-07, UX-01).
 */
export function NotFoundPage() {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  return (
    <section
      aria-labelledby="not-found-title"
      className="page"
      data-breakpoint={breakpoint}
      style={{
        inlineSize: '100%',
        minInlineSize: 0,
        overflowWrap: 'anywhere',
        textAlign: 'start',
      }}
    >
      <div className="stack" style={{ inlineSize: '100%', minInlineSize: 0 }}>
        <h2 id="not-found-title" style={{ minInlineSize: 0, overflowWrap: 'anywhere' }}>
          {shellText(locale, 'notFound')}
        </h2>
        <EmptyState />
      </div>
    </section>
  );
}
