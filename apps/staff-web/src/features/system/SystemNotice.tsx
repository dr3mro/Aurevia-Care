import { Button } from '../../components/Button';
import { useI18n } from '../../i18n/I18nProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { systemText } from './messages';
import type { SystemMessageKey } from './messages';

/** Props for a fixed, data-free system notice with one primary action. */
export interface SystemNoticeProps {
  /** Message key for the single title. */
  titleKey: SystemMessageKey;
  /** Message key for the plain explanation. */
  detailKey: SystemMessageKey;
  /** Message key for the one primary action. */
  actionKey: SystemMessageKey;
  /**
   * Action handler, owned by the page: shell-rendered pages use in-app
   * navigation, while the standalone unsupported page uses a document load so
   * the client-support check runs again (WEB-07).
   */
  onAction: () => void;
}

/**
 * Presentational surface shared by the signed-out, not-found and unsupported
 * pages. It renders only translated text and one action: no fixtures, no
 * network call and no browser storage (WEB-03, WEB-07).
 *
 * The `page` utility supplies the safe-area-aware logical padding and the
 * centred reading measure; `stack` keeps the title, detail and action in one
 * column from 320 px upward. Inline styles only keep long English and Arabic
 * strings inside the inline space without a horizontal scroll (WEB-07, UX-01);
 * direction stays with the document, so the same markup mirrors under RTL.
 */
export function SystemNotice({
  titleKey,
  detailKey,
  actionKey,
  onAction,
}: SystemNoticeProps) {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const title = systemText(locale, titleKey);
  return (
    <section
      aria-label={title}
      className="page stack"
      data-breakpoint={breakpoint}
      style={{
        minInlineSize: 0,
        textAlign: 'start',
        overflowWrap: 'anywhere',
      }}
    >
      <h1 style={{ overflowWrap: 'anywhere', whiteSpace: 'normal' }}>{title}</h1>
      <p style={{ overflowWrap: 'anywhere', whiteSpace: 'normal' }}>
        {systemText(locale, detailKey)}
      </p>
      <Button onClick={onAction}>{systemText(locale, actionKey)}</Button>
    </section>
  );
}
