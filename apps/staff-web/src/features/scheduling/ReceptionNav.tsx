import { useI18n } from '../../i18n/I18nProvider';
import { themeTokens } from '../../theme/tokens';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { schedulingText, schedulingTextWithValue } from './messages';
import type { SchedulingMessageKey } from './messages';

/** Props for the presentational reception navigation rail. */
export interface ReceptionNavProps {
  unreadMessages: number;
  roleName: string;
  userName: string;
  idleMinutes: number;
}

/** The five reception destinations; only Today is active on this screen. */
const navItems: readonly { id: string; labelKey: SchedulingMessageKey }[] = [
  { id: 'today', labelKey: 'navToday' },
  { id: 'patients', labelKey: 'navPatients' },
  { id: 'schedule', labelKey: 'navSchedule' },
  { id: 'messages', labelKey: 'navMessages' },
  { id: 'finance', labelKey: 'navFinance' },
];

/**
 * Draw the reception navigation as a dark leading rail, or as a full-width
 * top bar on compact viewports. This is presentational only: the shared app
 * shell still owns real navigation, and no destination here changes route.
 * The inverse surface uses the existing dark token set rather than a literal
 * colour, and every side-aware style is logical so the rail mirrors under RTL.
 */
export function ReceptionNav({ unreadMessages, roleName, userName, idleMinutes }: ReceptionNavProps) {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const compact = breakpoint === 'compact';
  const inverse = themeTokens('dark');
  const layout = compact ? 'top-bar' : 'side-column';

  return (
    <nav
      aria-label={schedulingText(locale, 'navLabel')}
      data-layout={layout}
      data-breakpoint={compact ? 'compact' : 'expanded'}
      style={{
        display: 'flex',
        flexDirection: compact ? 'row' : 'column',
        alignItems: compact ? 'center' : 'stretch',
        inlineSize: compact ? '100%' : '230px',
        flexShrink: 0,
        gap: inverse.spacing.md,
        paddingBlock: inverse.spacing.lg, paddingInline: inverse.spacing.lg,
        background: inverse.background,
        color: inverse.foreground,
        overflowWrap: 'anywhere',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: inverse.spacing.sm }}>
        <span aria-hidden="true" style={{ color: inverse.accent }}>A</span>
        <strong>{schedulingText(locale, 'brandName')}</strong>
      </div>
      <ul
        style={{
          display: 'flex',
          flexDirection: compact ? 'row' : 'column',
          flexWrap: 'wrap',
          gap: inverse.spacing.sm,
          listStyle: 'none',
          marginBlock: 0,
          marginInline: 0,
          paddingInline: 0,
          inlineSize: '100%',
        }}
      >
        {navItems.map((item) => (
          <li key={item.id} style={{ minInlineSize: 0 }}>
            <button
              type="button"
              className="touch"
              data-breakpoint={compact ? 'compact' : breakpoint}
              aria-current={item.id === 'today' ? 'page' : undefined}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: inverse.spacing.sm,
                inlineSize: '100%',
                paddingBlock: inverse.spacing.sm,
                paddingInline: inverse.spacing.md,
                border: `1px solid ${item.id === 'today' ? inverse.accent : inverse.border}`,
                borderRadius: inverse.spacing.sm,
                background: item.id === 'today' ? inverse.accent : 'transparent',
                color: item.id === 'today' ? inverse.background : inverse.foreground,
                cursor: 'pointer',
              }}
            >
              <span>{schedulingText(locale, item.labelKey)}</span>
              {item.id === 'messages' && unreadMessages > 0 ? (
                <span data-testid="messages-unread">
                  {schedulingTextWithValue(locale, 'navUnread', unreadMessages)}
                </span>
              ) : null}
            </button>
          </li>
        ))}
      </ul>
      <div style={{ marginBlockStart: 'auto', display: 'grid', gap: inverse.spacing.sm }}>
        <span>{roleName}</span>
        <strong>{userName}</strong>
        <span>{schedulingTextWithValue(locale, 'navIdle', idleMinutes)}</span>
      </div>
    </nav>
  );
}
