import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import { formatUnreadCount } from './domain';
import { unreadBadgeLabel } from './labels';

/** Props for the reusable header unread badge. */
export interface UnreadBadgeProps {
  /** Unread count reported by the inbox; zero hides the badge. */
  count: number;
}

/**
 * Present a bounded unread count without reordering digits under right-to-left
 * layout (MSG-02, UX-01). The numeral is isolated as a left-to-right run and the
 * accessible name is a translated, plural-aware sentence; no raw text is mixed
 * into the announcement. Mounting this badge in the header is a separate story.
 *
 * @param props component props
 * @returns nothing for an empty inbox, otherwise the tokenized badge
 */
export function UnreadBadge({ count }: UnreadBadgeProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const label = formatUnreadCount(count);

  if (label === '') {
    return null;
  }

  return (
    <span
      role="status"
      aria-label={unreadBadgeLabel(locale, count)}
      style={{
        display: 'inline-grid',
        placeItems: 'center',
        minInlineSize: tokens.spacing.md,
        paddingBlock: tokens.spacing.sm,
        paddingInline: tokens.spacing.sm,
        background: tokens.accent,
        color: tokens.background,
        borderRadius: tokens.spacing.sm,
        textAlign: 'center',
      }}
    >
      <bdi dir="ltr">
        <b>{label}</b>
      </bdi>
    </span>
  );
}
