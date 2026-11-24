import { useState } from 'react';
import type { CSSProperties } from 'react';
import { Button } from '../../../components/Button';
import { Dialog } from '../../../components/Dialog';
import { FormField } from '../../../components/FormField';
import { MixedScriptText } from '../../../components/MixedScriptText';
import { formatInstant } from '../../../i18n/format';
import type { Locale } from '../../../i18n/messages';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import { useBreakpoint } from '../../../theme/useBreakpoint';
import { folderCount, folderWordKey, isSelectableRecipient, recipientKindWordKey } from './domain';
import { mailText } from './labels';
import type {
  ComposeDraft,
  ComposeMode,
  DirectoryEntry,
  MailActor,
  MailAttachmentView,
  MailFolder,
  MailMessageView,
  MailOverviewView,
} from './types';
import { MAIL_FOLDERS } from './types';

/** Recipient list a confirmation must show before anything is published. */
export interface MailConfirmation {
  readonly recipients: readonly string[];
  readonly files: readonly string[];
}

/** Props for the presentational staff-mail screen. */
export interface MailScreenProps {
  readonly view: MailOverviewView;
  readonly directory: readonly DirectoryEntry[];
  readonly actor: MailActor;
  readonly crossFacilityEnabled: boolean;
  readonly selfValue: string;
  readonly draft: ComposeDraft;
  readonly stagedFiles: readonly MailAttachmentView[];
  readonly confirmation: MailConfirmation | undefined;
  readonly onDraftChange: (patch: Partial<ComposeDraft>) => void;
  readonly onAddRecipient: (field: 'to' | 'cc', entry: DirectoryEntry) => void;
  readonly onRemoveRecipient: (field: 'to' | 'cc', value: string) => void;
  readonly onAttach: (attachment: MailAttachmentView) => void;
  readonly onStartCompose: (mode: ComposeMode, message?: MailMessageView) => void;
  readonly onSend: () => void;
  readonly onConfirm: () => void;
  readonly onCancelConfirm: () => void;
}

/** One folder row with its derived count and an accessible current marker. */
function FolderRow({
  folder,
  count,
  active,
  locale,
  onSelect,
}: {
  readonly folder: MailFolder;
  readonly count: number;
  readonly active: boolean;
  readonly locale: Locale;
  readonly onSelect: (folder: MailFolder) => void;
}) {
  const { tokens } = useTheme();
  return (
    <li>
      <button
        type="button"
        className="touch"
        data-folder={folder}
        aria-current={active ? 'true' : undefined}
        onClick={() => onSelect(folder)}
        style={{
          inlineSize: '100%',
          textAlign: 'start',
          padding: tokens.spacing.sm,
          border: `1px solid ${tokens.border}`,
          background: active ? tokens.accent : tokens.background,
          color: active ? tokens.background : tokens.foreground,
        }}
      >
        {mailText(locale, folderWordKey(folder))} <bdi dir="ltr">({count})</bdi>
      </button>
    </li>
  );
}

/** One message row in the selected folder. */
function MessageRow({
  message,
  active,
  locale,
  onSelect,
}: {
  readonly message: MailMessageView;
  readonly active: boolean;
  readonly locale: Locale;
  readonly onSelect: (id: string) => void;
}) {
  const { tokens } = useTheme();
  return (
    <li>
      <button
        type="button"
        className="touch"
        data-message={message.id}
        aria-current={active ? 'true' : undefined}
        onClick={() => onSelect(message.id)}
        style={{
          display: 'grid',
          gap: tokens.spacing.sm,
          inlineSize: '100%',
          textAlign: 'start',
          padding: tokens.spacing.sm,
          border: `1px solid ${tokens.border}`,
          background: active ? tokens.accent : tokens.background,
          color: active ? tokens.background : tokens.foreground,
        }}
      >
        <MixedScriptText text={message.subject} lang="en" />
        <span>{formatInstant(message.sentAt, message.timeZone, locale)}</span>
      </button>
    </li>
  );
}

/** The reading pane for one selected message, with its reply commands. */
function ReadingPane({
  message,
  locale,
  onStartCompose,
}: {
  readonly message: MailMessageView | undefined;
  readonly locale: Locale;
  readonly onStartCompose: (mode: ComposeMode, message?: MailMessageView) => void;
}) {
  const { tokens } = useTheme();
  if (message === undefined) {
    return <p role="status">{mailText(locale, 'noSelection')}</p>;
  }
  const recipients = [...message.to, ...message.cc].map((recipient) => recipient.value).join(', ');
  return (
    <section
      aria-label={mailText(locale, 'readingPaneLabel')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <MixedScriptText text={message.subject} lang="en" />
      <p>
        {mailText(locale, 'fromLabel')}: <MixedScriptText text={message.from.label} lang="en" />
      </p>
      <p>
        {mailText(locale, 'toLabel')}: <MixedScriptText text={recipients} lang="en" />
      </p>
      <p>
        {mailText(locale, 'sentLabel')}: {formatInstant(message.sentAt, message.timeZone, locale)}{' '}
        <bdi dir="ltr">{message.timeZone}</bdi>
      </p>
      <MixedScriptText text={message.body} lang="en" />
      <div>
        {mailText(locale, 'attachmentsLabel')}:{' '}
        {message.attachments.map((attachment) => (
          <bdi key={attachment.documentId} dir="ltr">
            {attachment.fileName}{' '}
          </bdi>
        ))}
      </div>
      <div className="cluster">
        <Button variant="secondary" onClick={() => onStartCompose('reply', message)}>
          {mailText(locale, 'reply')}
        </Button>
        <Button variant="secondary" onClick={() => onStartCompose('reply-all', message)}>
          {mailText(locale, 'replyAll')}
        </Button>
        <Button variant="secondary" onClick={() => onStartCompose('forward', message)}>
          {mailText(locale, 'forward')}
        </Button>
      </div>
    </section>
  );
}

/** A single To or Cc field: a scoped picker plus removable recipient chips. */
function RecipientField({
  field,
  draft,
  directory,
  actor,
  crossFacilityEnabled,
  selfValue,
  locale,
  onAddRecipient,
  onRemoveRecipient,
}: {
  readonly field: 'to' | 'cc';
  readonly draft: ComposeDraft;
  readonly directory: readonly DirectoryEntry[];
  readonly actor: MailActor;
  readonly crossFacilityEnabled: boolean;
  readonly selfValue: string;
  readonly locale: Locale;
  readonly onAddRecipient: (field: 'to' | 'cc', entry: DirectoryEntry) => void;
  readonly onRemoveRecipient: (field: 'to' | 'cc', value: string) => void;
}) {
  const { tokens } = useTheme();
  const [chosen, setChosen] = useState('');
  const selected = field === 'to' ? draft.to : draft.cc;
  const available = directory.filter(
    (entry) => isSelectableRecipient(entry, actor, crossFacilityEnabled) && entry.value !== selfValue,
  );
  const option = available.find((entry) => entry.value === chosen);
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <label htmlFor={`mail-picker-${field}`}>
        {mailText(locale, field === 'to' ? 'toPickerLabel' : 'ccPickerLabel')}
      </label>
      <div className="cluster">
        <select
          id={`mail-picker-${field}`}
          className="field touch"
          value={chosen}
          onChange={(event) => setChosen(event.target.value)}
        >
          <option value="">{mailText(locale, 'directoryLabel')}</option>
          {available.map((entry) => (
            <option key={entry.value} value={entry.value}>
              {entry.label} · {mailText(locale, recipientKindWordKey(entry.kind))}
              {entry.facilityId !== actor.facilityId ? ` · ${mailText(locale, 'otherFacility')}` : ''}
            </option>
          ))}
        </select>
        <Button
          variant="secondary"
          onClick={() => {
            if (option !== undefined) {
              onAddRecipient(field, option);
              setChosen('');
            }
          }}
        >
          {mailText(locale, field === 'to' ? 'addTo' : 'addCc')}
        </Button>
      </div>
      <ul style={{ listStyle: 'none', paddingInlineStart: 0 }} data-recipient-list={field}>
        {selected.map((recipient) => (
          <li key={`${recipient.kind}:${recipient.value}`}>
            <button
              type="button"
              className="touch"
              aria-label={`${mailText(locale, 'removeRecipient')}: ${recipient.value}`}
              onClick={() => onRemoveRecipient(field, recipient.value)}
            >
              {recipient.label} <bdi dir="ltr">{recipient.value}</bdi>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** The versioned compose form, including staged file names and the send gate. */
function Composer({
  draft,
  directory,
  actor,
  crossFacilityEnabled,
  selfValue,
  stagedFiles,
  locale,
  onDraftChange,
  onAddRecipient,
  onRemoveRecipient,
  onAttach,
  onSend,
}: {
  readonly draft: ComposeDraft;
  readonly directory: readonly DirectoryEntry[];
  readonly actor: MailActor;
  readonly crossFacilityEnabled: boolean;
  readonly selfValue: string;
  readonly stagedFiles: readonly MailAttachmentView[];
  readonly locale: Locale;
  readonly onDraftChange: (patch: Partial<ComposeDraft>) => void;
  readonly onAddRecipient: (field: 'to' | 'cc', entry: DirectoryEntry) => void;
  readonly onRemoveRecipient: (field: 'to' | 'cc', value: string) => void;
  readonly onAttach: (attachment: MailAttachmentView) => void;
  readonly onSend: () => void;
}) {
  const { tokens } = useTheme();
  const [chosenFile, setChosenFile] = useState('');
  const file = stagedFiles.find((candidate) => candidate.documentId === chosenFile);
  return (
    <section
      aria-label={mailText(locale, 'composeLabel')}
      style={{ display: 'grid', gap: tokens.spacing.md, border: `1px solid ${tokens.border}`, padding: tokens.spacing.md }}
    >
      <h3>{mailText(locale, 'composeLabel')}</h3>
      <RecipientField
        field="to"
        draft={draft}
        directory={directory}
        actor={actor}
        crossFacilityEnabled={crossFacilityEnabled}
        selfValue={selfValue}
        locale={locale}
        onAddRecipient={onAddRecipient}
        onRemoveRecipient={onRemoveRecipient}
      />
      <RecipientField
        field="cc"
        draft={draft}
        directory={directory}
        actor={actor}
        crossFacilityEnabled={crossFacilityEnabled}
        selfValue={selfValue}
        locale={locale}
        onAddRecipient={onAddRecipient}
        onRemoveRecipient={onRemoveRecipient}
      />
      <FormField
        id="mail-subject"
        label={mailText(locale, 'subjectLabel')}
        value={draft.subject}
        onChange={(subject) => onDraftChange({ subject })}
      />
      <label htmlFor="mail-body">{mailText(locale, 'bodyLabel')}</label>
      <textarea
        id="mail-body"
        className="field touch"
        dir="auto"
        value={draft.body}
        onChange={(event) => onDraftChange({ body: event.target.value })}
        style={{ minHeight: '72px', padding: tokens.spacing.sm }}
      />
      <label htmlFor="mail-attach">{mailText(locale, 'attachLabel')}</label>
      <div className="cluster">
        <select
          id="mail-attach"
          className="field touch"
          value={chosenFile}
          onChange={(event) => setChosenFile(event.target.value)}
        >
          <option value="">{mailText(locale, 'attachmentsLabel')}</option>
          {stagedFiles.map((candidate) => (
            <option key={candidate.documentId} value={candidate.documentId}>
              {candidate.fileName}
            </option>
          ))}
        </select>
        <Button
          variant="secondary"
          onClick={() => {
            if (file !== undefined) {
              onAttach(file);
              setChosenFile('');
            }
          }}
        >
          {mailText(locale, 'attachLabel')}
        </Button>
      </div>
      <p data-testid="mail-attachment-hint">
        {mailText(locale, 'attachmentHint').replace(
          '{files}',
          draft.attachments.map((attachment) => attachment.fileName).join(', '),
        )}
      </p>
      <Button onClick={onSend}>{mailText(locale, 'send')}</Button>
    </section>
  );
}

/** The explicit recipient confirmation, mutually exclusive with conflict recovery. */
function ConfirmationDialog({
  confirmation,
  locale,
  onConfirm,
  onCancel,
}: {
  readonly confirmation: MailConfirmation | undefined;
  readonly locale: Locale;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
}) {
  return (
    <Dialog
      open={confirmation !== undefined}
      title={mailText(locale, 'confirmTitle')}
      closeLabel={mailText(locale, 'cancel')}
      onClose={onCancel}
    >
      {confirmation === undefined ? null : (
        <>
          <p>{mailText(locale, 'confirmDetail')}</p>
          <p data-testid="mail-confirmation-recipients">
            {mailText(locale, 'recipientsLabel')}: {confirmation.recipients.join(', ')}
          </p>
          <p data-testid="mail-confirmation-files">
            {mailText(locale, 'attachmentsLabel')}: {confirmation.files.join(', ')}
          </p>
          <div className="cluster">
            <Button onClick={onConfirm}>{mailText(locale, 'confirm')}</Button>
            <Button variant="secondary" onClick={onCancel}>
              {mailText(locale, 'cancel')}
            </Button>
          </div>
        </>
      )}
    </Dialog>
  );
}

/**
 * Compose the folder list, message list, reading pane and compose form for one
 * tenant's mail (MSG-02, MSG-03, WEB-09). All durable state and commands belong
 * to `MailPage`; only folder, message and picker selections are local here.
 * Every visible string is bilingual, layout uses logical properties, and
 * identifiers render as isolated left-to-right runs.
 */
export function MailScreen({
  view,
  directory,
  actor,
  crossFacilityEnabled,
  selfValue,
  draft,
  stagedFiles,
  confirmation,
  onDraftChange,
  onAddRecipient,
  onRemoveRecipient,
  onAttach,
  onStartCompose,
  onSend,
  onConfirm,
  onCancelConfirm,
}: MailScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const [activeFolder, setActiveFolder] = useState<MailFolder>('inbox');
  const [selectedId, setSelectedId] = useState('');
  const folderMessages = view.messages.filter((message) => message.folder === activeFolder);
  const selected = folderMessages.find((message) => message.id === selectedId) ?? folderMessages[0];
  const layout: CSSProperties = {
    display: 'grid',
    gap: tokens.spacing.lg,
    gridTemplateColumns: breakpoint === 'compact' ? 'minmax(0, 1fr)' : '220px minmax(0, 1fr)',
    maxInlineSize: 'none',
  };
  return (
    <section className="page" data-breakpoint={breakpoint} aria-label={mailText(locale, 'title')}>
      <h2>{mailText(locale, 'title')}</h2>
      <p>{mailText(locale, 'syntheticNotice')}</p>
      <div className="grid-2" style={layout}>
        <section aria-label={mailText(locale, 'foldersLabel')}>
          <ul style={{ listStyle: 'none', paddingInlineStart: 0, display: 'grid', gap: tokens.spacing.sm }}>
            {MAIL_FOLDERS.map((folder) => (
              <FolderRow
                key={folder}
                folder={folder}
                count={folderCount(view.counts, folder)}
                active={folder === activeFolder}
                locale={locale}
                onSelect={setActiveFolder}
              />
            ))}
          </ul>
          <Button variant="secondary" onClick={() => onStartCompose('new')}>
            {mailText(locale, 'newMail')}
          </Button>
        </section>
        <section style={{ display: 'grid', gap: tokens.spacing.md }}>
          <h3>{mailText(locale, 'messagesLabel')}</h3>
          {folderMessages.length === 0 ? (
            <p role="status">{mailText(locale, 'emptyFolder')}</p>
          ) : null}
          <ul style={{ listStyle: 'none', paddingInlineStart: 0, display: 'grid', gap: tokens.spacing.sm }}>
            {folderMessages.map((message) => (
              <MessageRow
                key={message.id}
                message={message}
                active={message.id === selected?.id}
                locale={locale}
                onSelect={setSelectedId}
              />
            ))}
          </ul>
          <ReadingPane message={selected} locale={locale} onStartCompose={onStartCompose} />
        </section>
      </div>
      <Composer
        draft={draft}
        directory={directory}
        actor={actor}
        crossFacilityEnabled={crossFacilityEnabled}
        selfValue={selfValue}
        stagedFiles={stagedFiles}
        locale={locale}
        onDraftChange={onDraftChange}
        onAddRecipient={onAddRecipient}
        onRemoveRecipient={onRemoveRecipient}
        onAttach={onAttach}
        onSend={onSend}
      />
      <ConfirmationDialog
        confirmation={confirmation}
        locale={locale}
        onConfirm={onConfirm}
        onCancel={onCancelConfirm}
      />
    </section>
  );
}
