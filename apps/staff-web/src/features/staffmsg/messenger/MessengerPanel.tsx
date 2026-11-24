import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { Button } from '../../../components/Button';
import { EmptyState, ErrorState, LoadingState, StateView } from '../../../components/StateViews';
import { MixedScriptText } from '../../../components/MixedScriptText';
import { useI18n } from '../../../i18n/I18nProvider';
import { formatInstant } from '../../../i18n/format';
import type { Locale } from '../../../i18n/messages';
import { useTheme } from '../../../theme/ThemeProvider';
import type { TransportError } from '../../../api/transport';
import { hasComposableText, groupMessagesByDay, deliveryWordKey } from './domain';
import { messengerText, messengerTextWithValue } from './labels';
import { createMessengerRealtime } from './realtime';
import type { RealtimeDeps } from './realtime';
import type {
  ComposerDraft,
  ConnectionStatus,
  ConversationView,
  FailedSend,
  MessageView,
  MessengerView,
  PendingAttachment,
} from './types';
import { MESSENGER_VIEW_TIME_ZONE } from './types';

/** Observable load state of the tenant-scoped messenger data. */
export type MessengerLoadState = 'loading' | 'ready' | 'error';

/** Props for the instant-messenger panel (MSG-02, MSG-03, WEB-09). */
export interface MessengerPanelProps {
  readonly view: MessengerView;
  readonly organizationId: string;
  readonly facilityId: string;
  readonly selfSubject: string;
  readonly draft: ComposerDraft;
  readonly stagedFiles: readonly PendingAttachment[];
  readonly loadState: MessengerLoadState;
  readonly error: TransportError | undefined;
  readonly sendFailure: FailedSend | undefined;
  readonly openStream: RealtimeDeps['openStream'];
  readonly poll: RealtimeDeps['poll'];
  readonly schedule: RealtimeDeps['schedule'];
  readonly onRefresh: () => void;
  readonly onSelectConversation: (id: string) => void;
  readonly onDraftChange: (patch: Partial<ComposerDraft>) => void;
  readonly onStageFile: (file: PendingAttachment) => void;
  readonly onRemoveFile: (id: string) => void;
  readonly onSend: (text: string, files: readonly PendingAttachment[]) => void;
  readonly onRetry: (idempotencyKey: string) => void;
  readonly onReload: () => void;
  readonly onSignIn: () => void;
  readonly onCompare: () => void;
  readonly onCopy: () => void;
}

/** The always-visible safety notice; this channel is not an emergency line. */
function EmergencyBanner({ locale }: { readonly locale: Locale }) {
  return (
    <p role="note" data-testid="messenger-emergency-banner">
      {messengerText(locale, 'emergencyBanner')}
    </p>
  );
}

/** Quiet, non-focus-stealing connection status. */
function ConnectionNotice({ status, locale }: { readonly status: ConnectionStatus; readonly locale: Locale }) {
  const { tokens } = useTheme();
  const words: Record<ConnectionStatus, string> = {
    streaming: messengerText(locale, 'streaming'),
    reconnecting: messengerText(locale, 'reconnecting'),
    polling: messengerText(locale, 'polling'),
  };
  return (
    <p
      role="status"
      aria-live="polite"
      data-connection={status}
      style={{ color: tokens.foreground, marginInline: 0 }}
    >
      {messengerText(locale, 'connectionLabel')}: {words[status]}
    </p>
  );
}

/** One conversation row with a derived unread badge and an accessible marker. */
function ConversationRow({
  conversation,
  active,
  locale,
  onSelect,
}: {
  readonly conversation: ConversationView;
  readonly active: boolean;
  readonly locale: Locale;
  readonly onSelect: (id: string) => void;
}) {
  const { tokens } = useTheme();
  const unread = conversation.unreadCount > 0;
  return (
    <li>
      <button
        type="button"
        className="touch"
        data-conversation={conversation.id}
        aria-current={active ? 'true' : undefined}
        onClick={() => onSelect(conversation.id)}
        style={{
          inlineSize: '100%',
          textAlign: 'start',
          padding: tokens.spacing.sm,
          border: `1px solid ${tokens.border}`,
          background: active ? tokens.accent : tokens.background,
          color: active ? tokens.background : tokens.foreground,
        }}
      >
        <MixedScriptText text={conversation.topic} lang="en" />
        {unread ? (
          <bdi dir="ltr"> {messengerTextWithValue(locale, 'unreadBadge', conversation.unreadCount)}</bdi>
        ) : null}
      </button>
    </li>
  );
}

/** One isolated bubble. Outgoing bubbles carry their delivery state in words. */
function MessageBubble({ message, locale }: { readonly message: MessageView; readonly locale: Locale }) {
  const { tokens } = useTheme();
  const style: CSSProperties = {
    display: 'grid',
    gap: tokens.spacing.sm,
    padding: tokens.spacing.md,
    border: `1px solid ${tokens.border}`,
    background: message.outgoing ? tokens.accent : tokens.background,
    color: message.outgoing ? tokens.background : tokens.foreground,
    marginInlineStart: message.outgoing ? tokens.spacing.lg : 0,
    textAlign: 'start',
  };
  return (
    <li data-message={message.id} data-delivery={message.delivery} style={style}>
      <bdi lang={message.lang} dir="auto" data-bubble>
        {message.body}
      </bdi>
      <span>{formatInstant(message.sentAt, message.timeZone, locale)}</span>
      {message.outgoing ? (
        <span data-delivery-label>{messengerText(locale, deliveryWordKey(message.delivery))}</span>
      ) : (
        <span>{messengerText(locale, 'incomingLabel')}</span>
      )}
    </li>
  );
}

/** The thread, grouped into civil-day sections with a rendered date separator. */
function Thread({ messages, locale }: { readonly messages: readonly MessageView[]; readonly locale: Locale }) {
  const { tokens } = useTheme();
  const groups = groupMessagesByDay(messages, MESSENGER_VIEW_TIME_ZONE, locale);
  if (groups.length === 0) {
    return <p role="status">{messengerText(locale, 'emptyThread')}</p>;
  }
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.md }}>
      {groups.map((group) => (
        <section key={group.day} aria-label={group.label} data-day={group.day}>
          <h4>{group.label}</h4>
          <ul style={{ listStyle: 'none', paddingInlineStart: 0, display: 'grid', gap: tokens.spacing.sm }}>
            {group.messages.map((message) => (
              <MessageBubble key={message.id} message={message} locale={locale} />
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}

/** The attachment tray: staged files are listed but never sent by staging. */
function AttachmentTray({
  draft,
  stagedFiles,
  locale,
  onStageFile,
  onRemoveFile,
}: {
  readonly draft: ComposerDraft;
  readonly stagedFiles: readonly PendingAttachment[];
  readonly locale: Locale;
  readonly onStageFile: (file: PendingAttachment) => void;
  readonly onRemoveFile: (id: string) => void;
}) {
  const { tokens } = useTheme();
  const [chosenId, setChosenId] = useState('');
  const chosen = stagedFiles.find((file) => file.id === chosenId);
  return (
    <section
      aria-label={messengerText(locale, 'attachmentTrayLabel')}
      style={{ display: 'grid', gap: tokens.spacing.sm, border: `1px solid ${tokens.border}`, padding: tokens.spacing.md }}
    >
      <h4>{messengerText(locale, 'attachmentTrayLabel')}</h4>
      <label htmlFor="messenger-stage-file">{messengerText(locale, 'stageFileLabel')}</label>
      <div className="cluster">
        <select
          id="messenger-stage-file"
          className="field touch"
          value={chosenId}
          onChange={(event) => setChosenId(event.target.value)}
        >
          <option value="">{messengerText(locale, 'stageFileLabel')}</option>
          {stagedFiles.map((file) => (
            <option key={file.id} value={file.id}>
              {file.name}
            </option>
          ))}
        </select>
        <Button
          variant="secondary"
          onClick={() => {
            if (chosen !== undefined) {
              onStageFile(chosen);
              setChosenId('');
            }
          }}
        >
          {messengerText(locale, 'attachLabel')}
        </Button>
      </div>
      <ul data-testid="messenger-attachment-tray" style={{ listStyle: 'none', paddingInlineStart: 0 }}>
        {draft.attachments.length === 0 ? (
          <li>{messengerText(locale, 'emptyTray')}</li>
        ) : (
          draft.attachments.map((file) => (
            <li key={file.id} data-pending-file={file.id}>
              <bdi dir="auto">{file.name}</bdi>{' '}
              <button
                type="button"
                className="touch"
                aria-label={`${messengerText(locale, 'removeAttachment')}: ${file.name}`}
                onClick={() => onRemoveFile(file.id)}
              >
                {messengerText(locale, 'removeAttachment')}
              </button>
            </li>
          ))
        )}
      </ul>
    </section>
  );
}

/** The composer: an explicit Send is the only trigger that publishes anything. */
function Composer({
  draft,
  stagedFiles,
  sendFailure,
  locale,
  onDraftChange,
  onStageFile,
  onRemoveFile,
  onSend,
  onRetry,
}: {
  readonly draft: ComposerDraft;
  readonly stagedFiles: readonly PendingAttachment[];
  readonly sendFailure: FailedSend | undefined;
  readonly locale: Locale;
  readonly onDraftChange: (patch: Partial<ComposerDraft>) => void;
  readonly onStageFile: (file: PendingAttachment) => void;
  readonly onRemoveFile: (id: string) => void;
  readonly onSend: (text: string, files: readonly PendingAttachment[]) => void;
  readonly onRetry: (idempotencyKey: string) => void;
}) {
  const { tokens } = useTheme();
  // A failed send keeps its own typed text so retry never depends on a cleared
  // draft. Once the author edits again, the live draft becomes authoritative:
  // the retained text is a recovery aid, not a lock on the composer.
  const [editedAfterFailure, setEditedAfterFailure] = useState(false);
  useEffect(() => {
    setEditedAfterFailure(false);
  }, [sendFailure?.idempotencyKey]);
  const text = sendFailure !== undefined && !editedAfterFailure ? sendFailure.text : draft.text;
  const files = sendFailure !== undefined && !editedAfterFailure ? sendFailure.files : draft.attachments;
  const canSend = hasComposableText(text);
  const names = files.map((file) => file.name).join(', ');
  return (
    <section
      aria-label={messengerText(locale, 'composerLabel')}
      style={{ display: 'grid', gap: tokens.spacing.md, border: `1px solid ${tokens.border}`, padding: tokens.spacing.md }}
    >
      <h3>{messengerText(locale, 'composerLabel')}</h3>
      <label htmlFor="messenger-composer-body">{messengerText(locale, 'bodyLabel')}</label>
      <textarea
        id="messenger-composer-body"
        className="field touch"
        dir="auto"
        value={text}
        onChange={(event) => {
          setEditedAfterFailure(true);
          onDraftChange({ text: event.target.value });
        }}
        style={{ minHeight: '72px', padding: tokens.spacing.sm }}
      />
      {names.length > 0 ? (
        <p data-testid="messenger-pending-files">{messengerTextWithValue(locale, 'pendingFiles', names)}</p>
      ) : null}
      {sendFailure === undefined ? null : (
        <p role="alert" data-testid="messenger-send-failure">
          {messengerText(locale, 'sendFailed')}
        </p>
      )}
      <AttachmentTray
        draft={{ text, attachments: files }}
        stagedFiles={stagedFiles}
        locale={locale}
        onStageFile={(file) => {
          setEditedAfterFailure(true);
          onStageFile(file);
        }}
        onRemoveFile={(id) => {
          setEditedAfterFailure(true);
          onRemoveFile(id);
        }}
      />
      <div className="cluster">
        <Button disabled={!canSend} onClick={() => onSend(text, files)}>
          {messengerText(locale, 'send')}
        </Button>
        {sendFailure === undefined ? null : (
          <Button variant="secondary" onClick={() => onRetry(sendFailure.idempotencyKey)}>
            {messengerText(locale, 'retry')}
          </Button>
        )}
      </div>
    </section>
  );
}

/**
 * Compose the safety banner, conversation list, thread, composer and resilient
 * states for one facility's instant messages. The panel owns the realtime
 * session (a deliberate single-folder deviation from the screen recipe's
 * screen/page split): the stream producer, polling fallback and connection
 * state live in `realtime.ts`, and this component is its only consumer. All
 * durable data and commands belong to the page that supplies the props.
 */
export function MessengerPanel(props: MessengerPanelProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [connection, setConnection] = useState<ConnectionStatus>('reconnecting');
  const callbacks = useRef({
    openStream: props.openStream,
    poll: props.poll,
    schedule: props.schedule,
    onRefresh: props.onRefresh,
  });

  useLayoutEffect(() => {
    callbacks.current = {
      openStream: props.openStream,
      poll: props.poll,
      schedule: props.schedule,
      onRefresh: props.onRefresh,
    };
  });

  useEffect(() => {
    const session = createMessengerRealtime({
      openStream: (handlers, signal, lastEventId) =>
        callbacks.current.openStream(handlers, signal, lastEventId),
      poll: (cursor) => callbacks.current.poll(cursor),
      schedule: (callback, delayMs) => callbacks.current.schedule(callback, delayMs),
      onDataChanged: () => callbacks.current.onRefresh(),
      onStatus: setConnection,
    });
    session.start();
    return () => {
      session.stop();
    };
  }, [props.organizationId, props.facilityId]);

  // The emergency notice is a safety invariant, so it stays visible in every
  // state: a user who cannot send still needs to know this is not an emergency
  // channel.
  if (props.loadState === 'loading') {
    return (
      <>
        <EmergencyBanner locale={locale} />
        <LoadingState />
      </>
    );
  }
  if (props.loadState === 'error') {
    if (props.error === undefined) {
      return (
        <>
          <EmergencyBanner locale={locale} />
          <ErrorState />
        </>
      );
    }
    return (
      <>
        <EmergencyBanner locale={locale} />
        {StateView.forTransportError(props.error, {
          onReload: props.onReload,
          onSignIn: props.onSignIn,
          onCompare: props.onCompare,
          onCopy: props.onCopy,
        })}
      </>
    );
  }
  if (props.view.conversations.length === 0) {
    return (
      <>
        <EmergencyBanner locale={locale} />
        <EmptyState />
      </>
    );
  }

  const selected = props.view.conversations.find((entry) => entry.id === props.view.selectedConversationId);
  const layout: CSSProperties = {
    display: 'grid',
    gap: tokens.spacing.lg,
    gridTemplateColumns: '220px minmax(0, 1fr)',
    maxInlineSize: 'none',
  };
  return (
    <section className="page" data-breakpoint="expanded" aria-label={messengerText(locale, 'title')}>
      <h2>{messengerText(locale, 'title')}</h2>
      <EmergencyBanner locale={locale} />
      <p>{messengerText(locale, 'syntheticNotice')}</p>
      <ConnectionNotice status={connection} locale={locale} />
      <div className="grid-2" style={layout}>
        <section aria-label={messengerText(locale, 'conversationsLabel')}>
          <ul style={{ listStyle: 'none', paddingInlineStart: 0, display: 'grid', gap: tokens.spacing.sm }}>
            {props.view.conversations.map((entry) => (
              <ConversationRow
                key={entry.id}
                conversation={entry}
                active={entry.id === selected?.id}
                locale={locale}
                onSelect={props.onSelectConversation}
              />
            ))}
          </ul>
        </section>
        <section aria-label={messengerText(locale, 'threadLabel')}>
          <h3>{selected === undefined ? messengerText(locale, 'noConversation') : selected.topic}</h3>
          <Thread messages={props.view.messages} locale={locale} />
        </section>
      </div>
      <Composer
        draft={props.draft}
        stagedFiles={props.stagedFiles}
        sendFailure={props.sendFailure}
        locale={locale}
        onDraftChange={props.onDraftChange}
        onStageFile={props.onStageFile}
        onRemoveFile={props.onRemoveFile}
        onSend={props.onSend}
        onRetry={props.onRetry}
      />
    </section>
  );
}
