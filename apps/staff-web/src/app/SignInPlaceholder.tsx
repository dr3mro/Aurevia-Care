import { useI18n } from '../i18n/I18nProvider';
import { useBreakpoint } from '../theme/useBreakpoint';

/**
 * Static sign-in placeholder; identity tooling owns real authentication.
 *
 * Presentation only (WEB-07, UX-01): the same heading and note stay in the
 * accessibility tree at every width. The `page` utility supplies safe-area-aware
 * logical padding and a single-column reading measure; because responsive.css
 * has no dedicated placeholder class, the inline styles keep long English and
 * Arabic text wrapping without a horizontal scroll. Direction comes from the
 * document, so right-to-left mirrors without a local direction override.
 *
 * @returns The labelled, non-interactive placeholder section.
 */
export function SignInPlaceholder() {
  const { t } = useI18n();
  const breakpoint = useBreakpoint();
  return (
    <section
      className="page"
      data-breakpoint={breakpoint}
      style={{
        display: 'grid',
        minInlineSize: 0,
        overflowWrap: 'anywhere',
        textAlign: 'start',
      }}
    >
      <h2 style={{ marginBlock: 0, overflowWrap: 'anywhere', whiteSpace: 'normal' }}>
        {t('signIn')}
      </h2>
      <p style={{ marginBlock: 0, overflowWrap: 'anywhere', whiteSpace: 'normal' }}>
        {t('signInNote')}
      </p>
    </section>
  );
}
