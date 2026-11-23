import { useState } from 'react';
import type { CSSProperties } from 'react';
import { Button } from '../../components/Button';
import { defined } from '../../components/invariant';
import { Dialog } from '../../components/Dialog';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { ThemeTokens } from '../../theme/tokens';
import { useBreakpoint } from '../../theme/useBreakpoint';
import {
  canOpenAttachment,
  hasDraftContent,
  scanWordKey,
  stateTone,
  stateWordKey,
} from './domain';
import type { ThreadTone } from './domain';
import { inboxText } from './labels';
import type { Attachment, InboxView, Message, SendDraft, Thread, UnitProfile, WorkDraft } from './types';

/** Props for the presentational unit-inbox screen. */
export interface InboxScreenProps {
  readonly view: InboxView;
  readonly replyText: string;
  readonly workDraft: WorkDraft | undefined;
  readonly reasonError: string | undefined;
  readonly onReplyTextChange: (text: string) => void;
  readonly onSend: (draft: SendDraft) => void;
  readonly onWorkDraftChange: (draft: WorkDraft | undefined) => void;
  readonly onWorkSubmit: (draft: WorkDraft) => void;
  readonly onDiscardDraft: () => void;
}

/** State role styling; the word beside the role is the accessible signal. */
function toneStyle(tokens: ThemeTokens, tone: ThreadTone): CSSProperties {
  if (tone === 'urgent') {
    return { background: tokens.accent, color: tokens.background, border: `1px solid ${tokens.accent}` };
  }
  if (tone === 'waiting') {
    return { background: 'transparent', color: tokens.foreground, border: `2px solid ${tokens.border}` };
  }
  return { background: tokens.background, color: tokens.foreground, border: `1px solid ${tokens.border}` };
}

/** The bordered MSG-06 service strip shown before a reply is sent. */
function UnitStrip({ unit, locale }: { readonly unit: UnitProfile; readonly locale: Locale }) {
  const { tokens } = useTheme();
  const facts = [
    [inboxText(locale, 'serviceHours'), unit.serviceHours],
    [inboxText(locale, 'responseTarget'), unit.responseTarget],
    [inboxText(locale, 'owner'), unit.owner],
    [inboxText(locale, 'backup'), unit.backup],
  ] as const;
  return (
    <dl
      aria-label={inboxText(locale, 'serviceStripLabel')}
      style={{
        display: 'grid',
        gap: tokens.spacing.sm,
        border: `1px solid ${tokens.border}`,
        padding: tokens.spacing.md,
      }}
    >
      {facts.map(([label, value]) => (
        <div key={label} style={{ display: 'flex', gap: tokens.spacing.md }}>
          <dt style={{ fontWeight: 'bold' }}>{label}</dt>
          <dd style={{ marginInlineStart: 0, overflowWrap: 'anywhere' }}>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** One thread row with identity, time, state word, owner and unread marker. */
function ThreadRow({
  thread,
  locale,
  active,
  onSelect,
}: {
  readonly thread: Thread;
  readonly locale: Locale;
  readonly active: boolean;
  readonly onSelect: (id: string) => void;
}) {
  const { tokens } = useTheme();
  const tone = stateTone(thread.state);
  const owner = thread.owner === '' ? inboxText(locale, 'unassigned') : thread.owner;
  return (
    <li>
      <button
        type="button"
        className="touch"
        data-thread-state={thread.state}
        data-open={active}
        aria-current={active ? 'true' : undefined}
        aria-label={`${thread.patientName.latinName}: ${thread.topic}`}
        onClick={() => onSelect(thread.id)}
        style={{
          display: 'grid',
          gap: tokens.spacing.sm,
          inlineSize: '100%',
          textAlign: 'start',
          padding: tokens.spacing.md,
          border: `1px solid ${tokens.border}`,
          background: active ? tokens.accent : tokens.background,
          color: active ? tokens.background : tokens.foreground,
          overflowWrap: 'anywhere',
        }}
      >
        <span style={{ display: 'flex', justifyContent: 'space-between', gap: tokens.spacing.sm }}>
          <strong>{thread.patientName.latinName}</strong>
          <span>{formatInstant(thread.lastActivityAt, thread.timeZone, locale)}</span>
        </span>
        <span>{thread.topic}</span>
        <span style={{ display: 'flex', gap: tokens.spacing.md }}>
          <span data-tone={tone} style={{ ...toneStyle(tokens, tone), paddingInline: tokens.spacing.sm }}>
            {inboxText(locale, stateWordKey(thread.state))}
          </span>
          <span>{owner}</span>
          {thread.unread ? <span>{inboxText(locale, 'unread')}</span> : null}
        </span>
      </button>
    </li>
  );
}

/** An attachment chip; only a clean scan becomes an openable control. */
function AttachmentChip({
  attachment,
  locale,
  onOpen,
}: {
  readonly attachment: Attachment;
  readonly locale: Locale;
  readonly onOpen: (name: string) => void;
}) {
  const { tokens } = useTheme();
  const openable = canOpenAttachment(attachment.scanState);
  const content = (
    <>
      <span>{attachment.fileName}</span>
      <span>{attachment.sizeLabel}</span>
      <span>{inboxText(locale, scanWordKey(attachment.scanState))}</span>
    </>
  );
  const style: CSSProperties = {
    display: 'flex',
    gap: tokens.spacing.md,
    padding: tokens.spacing.sm,
    border: `1px solid ${tokens.border}`,
    background: tokens.background,
    color: tokens.foreground,
    overflowWrap: 'anywhere',
  };
  return openable ? (
    <button
      type="button"
      className="touch"
      data-scan-state={attachment.scanState}
      aria-label={`Open attachment ${attachment.fileName}`}
      onClick={() => onOpen(attachment.fileName)}
      style={style}
    >
      {content}
    </button>
  ) : (
    <span data-scan-state={attachment.scanState} style={style}>
      {content}
    </span>
  );
}

/** One message bubble, with sender role, server time and attachment chips. */
function MessageBubble({
  message,
  locale,
  onOpenAttachment,
}: {
  readonly message: Message;
  readonly locale: Locale;
  readonly onOpenAttachment: (name: string) => void;
}) {
  const { tokens } = useTheme();
  const fromPatient = message.senderRole === 'patient';
  const role = fromPatient ? 'senderRolePatient' : 'senderRoleStaff';
  return (
    <article
      data-sender={message.senderRole}
      style={{
        display: 'grid',
        gap: tokens.spacing.sm,
        justifySelf: fromPatient ? 'start' : 'end',
        maxInlineSize: '90%',
        padding: tokens.spacing.md,
        border: `1px solid ${tokens.border}`,
        background: fromPatient ? tokens.background : tokens.accent,
        color: fromPatient ? tokens.foreground : tokens.background,
        overflowWrap: 'anywhere',
      }}
    >
      <span>
        {message.senderName} · {inboxText(locale, role)} ·{' '}
        {formatInstant(message.sentAt, message.timeZone, locale)}
      </span>
      <MixedScriptText text={message.body} lang="en" />
      {message.attachments.map((attachment) => (
        <AttachmentChip
          key={attachment.id}
          attachment={attachment}
          locale={locale}
          onOpen={onOpenAttachment}
        />
      ))}
    </article>
  );
}

/** Props for the reason dialog fields on a thread. */
interface ReasonFieldsProps {
  readonly draft: WorkDraft;
  readonly locale: Locale;
  readonly reasonError: string | undefined;
  readonly onDraftChange: (draft: WorkDraft) => void;
  readonly onSubmit: (draft: WorkDraft) => void;
}

/** Mandatory assignee and reason for an Assign or Resolve command. */
function ReasonFields({ draft, locale, reasonError, onDraftChange, onSubmit }: ReasonFieldsProps) {
  return (
    <div className="stack">
      <FormField
        id="inbox-assignee"
        label={inboxText(locale, 'assigneeLabel')}
        value={draft.assignee}
        onChange={(assignee) => onDraftChange({ ...draft, assignee })}
      />
      <FormField
        id="inbox-reason"
        label={inboxText(locale, 'reasonLabel')}
        value={draft.reason}
        onChange={(reason) => onDraftChange({ ...draft, reason })}
        {...(reasonError !== undefined ? { error: reasonError } : {})}
      />
      <Button onClick={() => onSubmit(draft)}>{inboxText(locale, 'recordAction')}</Button>
    </div>
  );
}

/** Props for the labelled reply composer. */
interface ComposerProps {
  readonly thread: Thread;
  readonly unitName: string;
  readonly pendingFiles: readonly string[];
  readonly locale: Locale;
  readonly replyText: string;
  readonly onReplyTextChange: (text: string) => void;
  readonly onSend: (draft: SendDraft) => void;
}

/** Labelled composer that names the recipient, staged files and send gate. */
function Composer({
  thread,
  unitName,
  pendingFiles,
  locale,
  replyText,
  onReplyTextChange,
  onSend,
}: ComposerProps) {
  const { tokens } = useTheme();
  const hint =
    pendingFiles.length === 0
      ? inboxText(locale, 'hintNoFiles')
      : inboxText(locale, 'hintFiles').replace('{files}', pendingFiles.join(', '));
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <label htmlFor="inbox-composer">
        {inboxText(locale, 'composerLabel')
          .replace('{patient}', thread.patientName.latinName)
          .replace('{unit}', unitName)}
      </label>
      <textarea
        id="inbox-composer"
        dir="auto"
        className="field"
        value={replyText}
        onChange={(event) => onReplyTextChange(event.target.value)}
        style={{ minHeight: '64px', padding: tokens.spacing.sm }}
      />
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: tokens.spacing.md }}>
        <p style={{ overflowWrap: 'anywhere' }}>{hint}</p>
        <Button
          onClick={() =>
            onSend({
              threadId: thread.id,
              version: thread.version,
              recipientName: thread.patientName.latinName,
              recipientId: thread.patientId,
              draft: replyText,
              pendingFiles: [...pendingFiles],
            })
          }
        >
          {inboxText(locale, 'send')}
        </Button>
      </div>
    </div>
  );
}

/** Props for the two overlays owned by the inbox screen. */
interface WorkDialogsProps {
  readonly workDraft: WorkDraft | undefined;
  readonly pendingId: string | undefined;
  readonly reasonError: string | undefined;
  readonly locale: Locale;
  readonly onWorkDraftChange: (draft: WorkDraft | undefined) => void;
  readonly onWorkSubmit: (draft: WorkDraft) => void;
  readonly onStay: () => void;
  readonly onLeave: () => void;
}

/** Reason dialog and unsaved-reply confirmation, rendered only when requested. */
function WorkDialogs({
  workDraft,
  pendingId,
  reasonError,
  locale,
  onWorkDraftChange,
  onWorkSubmit,
  onStay,
  onLeave,
}: WorkDialogsProps) {
  return (
    <>
      <Dialog
        open={workDraft !== undefined}
        title={inboxText(locale, 'actionDialogTitle').replace(
          '{thread}',
          workDraft === undefined ? '' : workDraft.threadId,
        )}
        closeLabel={inboxText(locale, 'cancel')}
        onClose={() => onWorkDraftChange(undefined)}
      >
        {workDraft !== undefined ? (
          <ReasonFields
            draft={workDraft}
            locale={locale}
            reasonError={reasonError}
            onDraftChange={onWorkDraftChange}
            onSubmit={onWorkSubmit}
          />
        ) : null}
      </Dialog>
      <Dialog
        open={pendingId !== undefined}
        title={inboxText(locale, 'discardTitle')}
        closeLabel={inboxText(locale, 'cancel')}
        onClose={onStay}
      >
        <p>{inboxText(locale, 'discardDetail')}</p>
        <Button onClick={onStay}>{inboxText(locale, 'stay')}</Button>
        <Button onClick={onLeave}>{inboxText(locale, 'leave')}</Button>
      </Dialog>
    </>
  );
}

/**
 * Compose the unit-inbox strip, thread list, conversation and composer (MSG-02,
 * MSG-06, WEB-09). Data, validation and commands belong to the page. The draft
 * text and selection are held here; leaving a thread with typed work asks first.
 * Layout uses logical properties and small subcomponents, and every state has
 * a word beside its role.
 */
export function InboxScreen({
  view,
  replyText,
  workDraft,
  reasonError,
  onReplyTextChange,
  onSend,
  onWorkDraftChange,
  onWorkSubmit,
  onDiscardDraft,
}: InboxScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const [selectedId, setSelectedId] = useState('');
  const [pendingId, setPendingId] = useState<string | undefined>(undefined);
  const [announcement, setAnnouncement] = useState('');
  const first = defined(view.threads[0], 'first inbox thread');
  const selected = view.threads.find((thread) => thread.id === selectedId) ?? first;

  function selectThread(id: string): void {
    if (id === selected.id) {
      return;
    }
    if (hasDraftContent(replyText)) {
      setPendingId(id);
      return;
    }
    setSelectedId(id);
  }

  return (
    <section
      className="page"
      data-breakpoint={breakpoint}
      data-inbox-layout={breakpoint === 'compact' ? 'stacked' : 'columns'}
      aria-label={inboxText(locale, 'title')}
      style={{ display: 'grid', gap: tokens.spacing.lg, maxInlineSize: 'none' }}
    >
      <h2 style={{ overflowWrap: 'anywhere' }}>
        {inboxText(locale, 'unitInboxHeading').replace('{unit}', view.unit.name)}
      </h2>
      <UnitStrip unit={view.unit} locale={locale} />
      <div className="grid-2" data-inbox-panels style={{ gridTemplateColumns: '320px minmax(0, 1fr)' }}>
        <section aria-label={inboxText(locale, 'threadListLabel')}>
          <ul style={{ listStyle: 'none', paddingInlineStart: 0, display: 'grid', gap: tokens.spacing.sm }}>
            {view.threads.map((thread) => (
              <ThreadRow
                key={thread.id}
                thread={thread}
                locale={locale}
                active={thread.id === selected.id}
                onSelect={selectThread}
              />
            ))}
          </ul>
        </section>
        <section
          aria-label={inboxText(locale, 'title')}
          style={{ display: 'grid', gap: tokens.spacing.md, border: `1px solid ${tokens.border}`, padding: tokens.spacing.md }}
        >
          <header style={{ display: 'flex', justifyContent: 'space-between', gap: tokens.spacing.md }}>
            <div>
              <strong>{selected.patientName.latinName}</strong>
              <p>{selected.topic}</p>
              <p>{inboxText(locale, 'connectionVerified')}</p>
            </div>
            <div style={{ display: 'flex', gap: tokens.spacing.sm }}>
              <Button
                variant="secondary"
                onClick={() =>
                  onWorkDraftChange({
                    threadId: selected.id,
                    version: selected.version,
                    action: 'assign',
                    reason: '',
                    assignee: view.unit.backup,
                  })
                }
              >
                {inboxText(locale, 'assign')}
              </Button>
              <Button
                variant="secondary"
                onClick={() =>
                  onWorkDraftChange({
                    threadId: selected.id,
                    version: selected.version,
                    action: 'resolve',
                    reason: '',
                    assignee: view.unit.backup,
                  })
                }
              >
                {inboxText(locale, 'resolve')}
              </Button>
            </div>
          </header>
          <div style={{ display: 'grid', gap: tokens.spacing.md }}>
            {selected.messages.length === 0 ? (
              <p>{inboxText(locale, 'noMessages')}</p>
            ) : (
              selected.messages.map((message) => (
                <MessageBubble
                  key={message.id}
                  message={message}
                  locale={locale}
                  onOpenAttachment={(name) => setAnnouncement(name)}
                />
              ))
            )}
          </div>
          <Composer
            thread={selected}
            unitName={view.unit.name}
            pendingFiles={view.pendingFileNames}
            locale={locale}
            replyText={replyText}
            onReplyTextChange={onReplyTextChange}
            onSend={onSend}
          />
          <p role="status">{announcement}</p>
        </section>
      </div>
      <WorkDialogs
        workDraft={workDraft}
        pendingId={pendingId}
        reasonError={reasonError}
        locale={locale}
        onWorkDraftChange={onWorkDraftChange}
        onWorkSubmit={onWorkSubmit}
        onStay={() => setPendingId(undefined)}
        onLeave={() => {
          const target = defined(pendingId, 'pending thread id');
          setSelectedId(target);
          setPendingId(undefined);
          onDiscardDraft();
        }}
      />
    </section>
  );
}
