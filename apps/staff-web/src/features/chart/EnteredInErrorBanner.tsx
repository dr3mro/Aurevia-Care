import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { EnteredInError } from './domain';
import { chartText } from './messages';

/** Props for the clinical entered-in-error alert (CLN-05). */
export interface EnteredInErrorBannerProps {
  detail: EnteredInError;
  locale: Locale;
}

/** Show who recorded an error, why, when and which recipients were affected. */
export function EnteredInErrorBanner({ detail, locale }: EnteredInErrorBannerProps) {
  const { tokens } = useTheme();
  return (
    <div
      role="alert"
      data-testid="entered-in-error"
      style={{
        border: `2px solid ${tokens.foreground}`,
        padding: tokens.spacing.md,
        display: 'grid',
        gap: tokens.spacing.sm,
      }}
    >
      <strong>{chartText(locale, 'enteredInError')}</strong>
      <p>{chartText(locale, 'enteredInErrorDetail')}</p>
      <dl style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <dt>{chartText(locale, 'signer')}</dt>
        <dd>{detail.actor}</dd>
        <dt>{chartText(locale, 'voidReason')}</dt>
        <dd>{detail.reason}</dd>
        <dt>{chartText(locale, 'affectedRecipients')}</dt>
        <dd>{detail.affectedRecipients.join(', ')}</dd>
        <dt>{chartText(locale, 'recordedAt')}</dt>
        <dd>
          <time dateTime={detail.recordedAt}>
            {formatInstant(detail.recordedAt, 'UTC', locale)}
          </time>
        </dd>
      </dl>
    </div>
  );
}
