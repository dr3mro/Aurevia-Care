import { Link } from '../../app/router';
import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { isSlaBreached } from './domain';
import { messagesMessage, messagesText, stateMessageKey, unitMessageKey } from './messages';
import type { Inbox, MessageTab, WorkQueue } from './types';
import { useUnreadCount } from './unreadStore';

/** Props for the presentational unit-inbox screen. */
export interface MessagesScreenProps {
  inbox: Inbox;
  workQueue: WorkQueue;
  tab: MessageTab;
  organizationId: string;
  /** Injected clock input so the SLA marker is deterministic in tests. */
  nowMs: number;
  onTabChange: (tab: MessageTab) => void;
}

/** Props for one tenant-scoped unread badge. */
interface UnreadBadgeProps {
  organizationId: string;
  conversationId: string;
  serverCount: number;
  locale: Locale;
}

/**
 * Show the server unread count unless a successful read on the conversation
 * screen has already replaced it. Calling the hook in a leaf keeps the number
 * of hooks stable when the list length changes.
 */
function UnreadBadge({ organizationId, conversationId, serverCount, locale }: UnreadBadgeProps) {
  const count = useUnreadCount(organizationId, conversationId, serverCount);
  return (
    <span data-testid={`unread-${conversationId}`} data-count={count}>
      {messagesMessage(locale, 'unreadCount', { count: String(count) })}
    </span>
  );
}

/**
 * Append the row's patient identifier and received time to the link's
 * accessible name. A worklist action must announce which patient and thread it
 * opens, not only the topic (accessibility patterns section 4).
 */
function RowIdentity({
  patientId,
  receivedAt,
  locale,
}: {
  patientId: string;
  receivedAt: string;
  locale: Locale;
}) {
  return (
    <span
      style={{
        position: 'absolute',
        width: 1,
        height: 1,
        overflow: 'hidden',
        clipPath: 'inset(50%)',
        whiteSpace: 'nowrap',
      }}
    >
      {` ${patientId} ${formatInstant(receivedAt, 'UTC', locale)}`}
    </span>
  );
}

/** Props for the SLA marker. */
interface SlaBadgeProps {
  breached: boolean;
  label: string;
}

/** Mark a breach with a distinct word and a token colour, never colour alone. */
function SlaBadge({ breached, label }: SlaBadgeProps) {
  const { tokens } = useTheme();
  const colour = breached ? tokens.accent : tokens.foreground;
  return (
    <span
      data-state={breached ? 'breached' : 'within'}
      style={{
        color: colour,
        borderInlineStart: `2px solid ${breached ? tokens.accent : tokens.border}`,
        paddingInlineStart: tokens.spacing.sm,
      }}
    >
      {label}
    </span>
  );
}

/** Render the two tabs and the selected table from server projections. */
export function MessagesScreen({
  inbox,
  workQueue,
  tab,
  organizationId,
  nowMs,
  onTabChange,
}: MessagesScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const unreadById = new Map(inbox.entries.map((entry) => [entry.conversation.id, entry.unreadCount]));
  const rows =
    tab === 'inbox'
      ? inbox.entries.map((entry) => ({
          conversation: entry.conversation,
          serverUnread: entry.unreadCount,
        }))
      : workQueue.entries.map((conversation) => ({
          conversation,
          serverUnread: unreadById.get(conversation.id) ?? 0,
        }));

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <div role="tablist" aria-label={messagesText(locale, 'messages')}>
        <button
          type="button"
          role="tab"
          id="messages-tab-inbox"
          aria-controls="messages-tabpanel"
          aria-selected={tab === 'inbox'}
          onClick={() => onTabChange('inbox')}
        >
          {messagesText(locale, 'inbox')}
        </button>
        <button
          type="button"
          role="tab"
          id="messages-tab-work-queue"
          aria-controls="messages-tabpanel"
          aria-selected={tab === 'work-queue'}
          onClick={() => onTabChange('work-queue')}
        >
          {messagesText(locale, 'workQueue')}
        </button>
      </div>
      <div
        role="tabpanel"
        id="messages-tabpanel"
        aria-labelledby={tab === 'inbox' ? 'messages-tab-inbox' : 'messages-tab-work-queue'}
      >
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
            {messagesText(locale, tab === 'inbox' ? 'inbox' : 'workQueue')}
          </caption>
          <thead>
            <tr>
              <th scope="col">{messagesText(locale, 'topic')}</th>
              <th scope="col">{messagesText(locale, 'careUnit')}</th>
              <th scope="col">{messagesText(locale, 'patientId')}</th>
              <th scope="col">{messagesText(locale, 'state')}</th>
              <th scope="col">{messagesText(locale, 'unread')}</th>
              <th scope="col">{messagesText(locale, 'received')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ conversation, serverUnread }) => {
              const breached = isSlaBreached(conversation, nowMs);
              return (
                <tr key={conversation.id}>
                  <th scope="row">
                    <Link to={`/messages/${encodeURIComponent(conversation.id)}`}>
                      {messagesText(locale, 'openConversation')}:{' '}
                      <MixedScriptText
                        text={conversation.topic ?? messagesText(locale, 'conversation')}
                        lang={locale}
                      />
                      <RowIdentity
                        patientId={conversation.patientId}
                        receivedAt={conversation.createdAt}
                        locale={locale}
                      />
                    </Link>
                  </th>
                  <td>
                    <MixedScriptText text={messagesText(locale, unitMessageKey[conversation.unitKey])} lang={locale} />
                  </td>
                  <td>
                    <code dir="ltr">{conversation.patientId}</code>
                  </td>
                  <td>
                    <MixedScriptText
                      text={messagesText(locale, stateMessageKey[conversation.state])}
                      lang={locale}
                    />{' '}
                    <SlaBadge
                      breached={breached}
                      label={messagesText(locale, breached ? 'slaBreached' : 'slaWithinTarget')}
                    />
                  </td>
                  <td>
                    <UnreadBadge
                      organizationId={organizationId}
                      conversationId={conversation.id}
                      serverCount={serverUnread}
                      locale={locale}
                    />
                  </td>
                  <td>
                    <time dateTime={conversation.createdAt}>
                      {formatInstant(conversation.createdAt, 'UTC', locale)}
                    </time>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
