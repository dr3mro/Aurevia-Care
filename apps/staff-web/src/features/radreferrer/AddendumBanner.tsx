import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { AddendumAcknowledgment } from './domain';
import { openAddenda } from './domain';
import { radreferrerText } from './labels';

/**
 * Split a translated sentence around its `{version}` placeholder into the text
 * before and after the marker. Both bundle messages always contain the marker,
 * so the returned parts are always strings and no nullish fallback is needed.
 *
 * @param text - Translated sentence containing exactly one `{version}` marker.
 * @returns The literal text before and after the marker.
 */
function aroundVersion(text: string): readonly [string, string] {
  const marker = '{version}';
  const at = text.indexOf(marker);
  return [text.slice(0, at), text.slice(at + marker.length)];
}

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
  /** Requested acknowledgment of one exact, tenant- and order-scoped receipt. */
  onAcknowledge: (addendum: AddendumAcknowledgment) => void;
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
  // Split each translated sentence around its named placeholder so the order-
  // local version digit is an isolated LTR run under an RTL interface.
  const [bodyLead, bodyTail] = aroundVersion(radreferrerText(locale, 'addendumBody'));
  const [actionLead, actionTail] = aroundVersion(radreferrerText(locale, 'acknowledgeVersion'));
  return (
    <div
      data-testid="addendum-banner"
      role="group"
      aria-label={radreferrerText(locale, 'addendumHeading')}
      style={{ color: tokens.foreground, borderColor: tokens.accent }}
    >
      {open.map((addendum) => {
        const headingId = `addendum-heading-${addendum.version}`;
        return (
          <section
            key={`${addendum.organizationId}:${addendum.orderId}:${addendum.version}`}
            data-version={addendum.version}
            aria-labelledby={headingId}
          >
            {/* Only the notification text is a live region; the control that
                acts on it stays outside, so a screen reader can reach it. */}
            <div role="status">
              <h3 id={headingId}>{radreferrerText(locale, 'addendumHeading')}</h3>
              <p>
                {bodyLead}
                <bdi dir="ltr">{addendum.version}</bdi>
                {bodyTail}
              </p>
            </div>
            <button
              type="button"
              aria-label={`${actionLead}${addendum.version}${actionTail}`}
              onClick={() => onAcknowledge(addendum)}
            >
              {radreferrerText(locale, 'acknowledge')}
            </button>
          </section>
        );
      })}
    </div>
  );
}
