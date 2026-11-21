import { Link } from '../../app/router';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { StateView } from '../../components/StateViews';
import type { TransportError } from '../../api/transport';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import {
  messagesText,
  senderMessageKey,
  workEventMessageKey,
} from './messages';
import type { ConfirmableAction, ConversationView } from './types';

/** Props for the presentational conversation screen. */
export interface ConversationScreenProps {
  view: ConversationView;
  replyDraft: string;
  replyError: string | undefined;
  commandError: TransportError | undefined;
  status: string | undefined;
  onReplyDraftChange: (value: string) => void;
  onReply: () => void;
  onAction: (action: ConfirmableAction) => void;
}

/** Display instant used when the conversation carries no facility zone. */
const DISPLAY_TIME_ZONE = 'UTC';

/** Button labels for each confirmable command, in a fixed order. */
const CONFIRMABLE_ACTIONS: readonly ConfirmableAction[] = ['assign', 'transfer', 'resolve', 'close'];

/** Render the ordered message trail and the available staff commands. */
export function ConversationScreen({
  view,
  replyDraft,
  replyError,
  commandError,
  status,
  onReplyDraftChange,
  onReply,
  onAction,
}: ConversationScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const conversation = view.conversation;
  const readOnly = conversation.state === 'closed';

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{messagesText(locale, 'conversation')}</h2>
        <p style={{ marginBlock: 0 }}>{messagesText(locale, 'conversationIntro')}</p>
        <p style={{ marginBlock: 0 }}>
          <b>{messagesText(locale, 'patientId')}:</b> <code dir="ltr">{conversation.patientId}</code>
        </p>
        <Link to="/messages">{messagesText(locale, 'backToMessages')}</Link>
      </header>

      {readOnly ? (
        <p role="note">{messagesText(locale, 'readOnlyNotice')}</p>
      ) : null}

      <section aria-label={messagesText(locale, 'messagesAndEvents')}>
        <h3>{messagesText(locale, 'reply')}</h3>
        {view.messages.length === 0 ? (
          <p>{messagesText(locale, 'noMessages')}</p>
        ) : (
          <ol aria-label={messagesText(locale, 'messages')}>
            {view.messages.map((message) => (
              <li key={message.id} style={{ paddingBlock: tokens.spacing.sm }}>
                <b>
                  <MixedScriptText
                    text={messagesText(locale, senderMessageKey[message.senderKind])}
                    lang={locale}
                  />
                </b>{' '}
                <time dateTime={message.createdAt}>
                  {formatInstant(message.createdAt, DISPLAY_TIME_ZONE, locale)}
                </time>
                <p style={{ marginBlock: 0 }}>
                  <MixedScriptText text={message.body} lang={locale} />
                </p>
              </li>
            ))}
          </ol>
        )}

        <h3>{messagesText(locale, 'events')}</h3>
        {view.events.length === 0 ? (
          <p>{messagesText(locale, 'noEvents')}</p>
        ) : (
          <ol aria-label={messagesText(locale, 'events')}>
            {view.events.map((event) => (
              <li key={event.id} style={{ paddingBlock: tokens.spacing.sm }}>
                <MixedScriptText
                  text={messagesText(locale, workEventMessageKey[event.eventType])}
                  lang={locale}
                />{' '}
                — <code dir="ltr">{event.actorSubject}</code>{' '}
                <time dateTime={event.occurredAt}>
                  {formatInstant(event.occurredAt, DISPLAY_TIME_ZONE, locale)}
                </time>
                {event.reason === '' ? null : (
                  <p style={{ marginBlock: 0 }}>
                    <MixedScriptText text={event.reason} lang={locale} />
                  </p>
                )}
              </li>
            ))}
          </ol>
        )}
      </section>

      {readOnly ? null : (
        <form
          aria-label={messagesText(locale, 'reply')}
          onSubmit={(event) => {
            event.preventDefault();
            onReply();
          }}
        >
          <label htmlFor="message-reply">{messagesText(locale, 'replyLabel')}</label>
          <textarea
            id="message-reply"
            value={replyDraft}
            onChange={(event) => onReplyDraftChange(event.target.value)}
            rows={3}
            style={{
              display: 'block',
              width: '100%',
              padding: tokens.spacing.sm,
              border: `1px solid ${tokens.border}`,
              background: tokens.background,
              color: tokens.foreground,
            }}
          />
          {replyError === undefined ? null : (
            <p id="message-reply-error" role="alert">
              {replyError}
            </p>
          )}
          <Button type="submit">{messagesText(locale, 'reply')}</Button>
        </form>
      )}

      {readOnly ? null : (
        <div role="group" aria-label={messagesText(locale, 'actions')}>
          {CONFIRMABLE_ACTIONS.map((action) => (
            <Button key={action} onClick={() => onAction(action)}>
              {messagesText(locale, action)}
            </Button>
          ))}
        </div>
      )}

      {commandError === undefined ? null : StateView.forTransportError(commandError)}
      {status === undefined ? null : (
        <p role="status" data-testid="conversation-status">
          {status}
        </p>
      )}
    </div>
  );
}
