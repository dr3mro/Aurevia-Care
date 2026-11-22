import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type { EnteredInError } from './domain';
import { chartText } from './messages';

/** Props for the clinical entered-in-error alert (CLN-05). */
export interface EnteredInErrorBannerProps {
  detail: EnteredInError;
  locale: Locale;
}

/**
 * Show who recorded an error, why, when and which recipients were affected.
 *
 * Width changes layout only, never the clinical fields or the alert semantics.
 * The `page` utility gives the banner the full content column and the
 * safe-area-aware logical padding; the `grid-2` utility stacks the field list on
 * a phone and places the same fields side by side from the medium band up, so
 * no clinical field is hidden at any width. Long English and Arabic values wrap
 * with logical styles instead of forcing a horizontal page scroll, and no fixed
 * block size or physical side is set so text stays readable at 200 percent zoom
 * and mirrors under right-to-left. The active band is exposed as
 * `data-breakpoint` so layout checks can assert it in a DOM-only environment.
 */
export function EnteredInErrorBanner({ detail, locale }: EnteredInErrorBannerProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  return (
    <div
      role="alert"
      data-testid="entered-in-error"
      data-breakpoint={breakpoint}
      className="page"
      style={{
        border: `2px solid ${tokens.foreground}`,
        display: 'grid',
        gap: tokens.spacing.sm,
        inlineSize: '100%',
        minInlineSize: 0,
        overflowWrap: 'anywhere',
        whiteSpace: 'normal',
        textAlign: 'start',
      }}
    >
      <strong>{chartText(locale, 'enteredInError')}</strong>
      <p>{chartText(locale, 'enteredInErrorDetail')}</p>
      <dl className="grid-2" style={{ gap: tokens.spacing.sm }}>
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
