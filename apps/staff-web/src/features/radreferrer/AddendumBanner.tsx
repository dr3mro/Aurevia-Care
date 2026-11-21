import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { AddendumAcknowledgment } from './domain';
import { openAddenda } from './domain';
import { radreferrerText } from './labels';

/** Props for the addendum acknowledgment banner shown above a report. */
export interface AddendumBannerProps {
  /** All addendum receipts delivered with the report. */
  addenda: readonly AddendumAcknowledgment[];
  /** Active tenant; receipts from another tenant are never shown. */
  organizationId: string;
  /** Order the report belongs to; receipts for other orders are ignored. */
  orderId: string;
  /** Interface language for the banner text and action. */
  locale: Locale;
  /** Requested acknowledgment of one exact addendum revision. */
  onAcknowledge: (version: number) => void;
}

/**
 * Show a banner for every still-open addendum of this report, with an explicit
 * acknowledge action for each exact revision. Once the receipt is acknowledged
 * the banner disappears; the parent owns sending the command and updating the
 * receipt. Scoping is repeated here as defence in depth over the API boundary.
 */
export function AddendumBanner({
  addenda,
  organizationId,
  orderId,
  locale,
  onAcknowledge,
}: AddendumBannerProps) {
  const { tokens } = useTheme();
  const open = openAddenda(addenda, organizationId, orderId);
  if (open.length === 0) {
    return null;
  }
  return (
    <div
      data-testid="addendum-banner"
      role="status"
      aria-label={radreferrerText(locale, 'addendumHeading')}
      style={{ color: tokens.foreground, borderColor: tokens.accent }}
    >
      {open.map((addendum) => (
        <section
          key={`${addendum.organizationId}:${addendum.orderId}:${addendum.version}`}
          data-version={addendum.version}
        >
          <strong>{radreferrerText(locale, 'addendumHeading')}</strong>
          <p>
            {radreferrerText(locale, 'addendumBody').replace(
              '{version}',
              String(addendum.version),
            )}
          </p>
          <button type="button" onClick={() => onAcknowledge(addendum.version)}>
            {radreferrerText(locale, 'acknowledge')}
          </button>
        </section>
      ))}
    </div>
  );
}
