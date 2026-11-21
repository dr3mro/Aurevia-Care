import { Button } from '../../components/Button';
import { useI18n } from '../../i18n/I18nProvider';
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
 */
export function SystemNotice({
  titleKey,
  detailKey,
  actionKey,
  onAction,
}: SystemNoticeProps) {
  const { locale } = useI18n();
  const title = systemText(locale, titleKey);
  return (
    <section aria-label={title}>
      <h1>{title}</h1>
      <p>{systemText(locale, detailKey)}</p>
      <Button onClick={onAction}>{systemText(locale, actionKey)}</Button>
    </section>
  );
}
