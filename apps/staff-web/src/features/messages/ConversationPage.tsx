import { useEffect, useRef, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useRoute } from '../../app/router';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { TransportError } from '../../api/transport';
import {
  assign,
  asMessagesTransportError,
  close,
  getConversation,
  read,
  reply,
  resolve,
  transfer,
} from './api';
import { ConversationScreen } from './ConversationScreen';
import {
  messagesText,
} from './messages';
import type { MessagesMessageKey } from './messages';
import type { ConfirmableAction, Conversation, ConversationView } from './types';
import { markConversationRead } from './unreadStore';

/** Bilingual titles for the four confirmation dialogs. */
const TITLE_KEY: Record<ConfirmableAction, MessagesMessageKey> = {
  assign: 'assignTitle',
  transfer: 'transferTitle',
  resolve: 'resolveTitle',
  close: 'closeTitle',
};

/** Bilingual confirm labels for the four confirmation dialogs. */
const CONFIRM_KEY: Record<ConfirmableAction, MessagesMessageKey> = {
  assign: 'confirmAssign',
  transfer: 'confirmTransfer',
  resolve: 'confirmResolve',
  close: 'confirmClose',
};

/** Bilingual status announcements after a successful command. */
const STATUS_KEY: Record<ConfirmableAction, MessagesMessageKey> = {
  assign: 'assigned',
  transfer: 'transferred',
  resolve: 'resolved',
  close: 'closed',
};

/** State collected by an open confirmation dialog. */
interface ConfirmState {
  action: ConfirmableAction;
  subject: string;
  reason: string;
  error: MessagesMessageKey | undefined;
}

/** Props for the loaded, tenant-scoped conversation content. */
interface ConversationContentProps {
  view: ConversationView;
  conversationId: string;
  organizationId: string;
}

/**
 * Own the mutable view, the reply draft and the confirmation dialog for one
 * loaded conversation. The component is keyed by tenant and conversation, so a
 * switch remounts it and the read-once guard starts fresh.
 */
function ConversationContent({
  view,
  conversationId,
  organizationId,
}: ConversationContentProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [currentView, setCurrentView] = useState<ConversationView>(view);
  const [replyDraft, setReplyDraft] = useState('');
  const [replyError, setReplyError] = useState<MessagesMessageKey | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [status, setStatus] = useState<string | undefined>(undefined);
  const [confirm, setConfirm] = useState<ConfirmState | undefined>(undefined);
  const readFor = useRef<string | undefined>(undefined);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const confirmOpen = confirm !== undefined;

  // An in-page confirmation keeps every field keyboard reachable; focus starts
  // on the safe Cancel choice, never on the irreversible command.
  useEffect(() => {
    if (confirmOpen) {
      cancelRef.current!.focus();
    }
  }, [confirmOpen]);

  useEffect(() => {
    setCurrentView(view);
  }, [view]);

  // Opening a conversation records exactly one read for this tenant and id.
  // The ref also survives StrictMode's second effect run without a second POST.
  useEffect(() => {
    const key = `${organizationId}\u0000${conversationId}`;
    if (readFor.current === key) {
      return;
    }
    readFor.current = key;
    read(conversationId, view.conversation.version, organizationId).then(
      (updated: Conversation) => {
        setCurrentView((current) => ({ ...current, conversation: updated }));
        markConversationRead(organizationId, conversationId, 0);
      },
      (failure: unknown) => setCommandError(asMessagesTransportError(failure)),
    );
  }, [view, conversationId, organizationId]);

  async function handleReply(): Promise<void> {
    if (replyDraft.trim() === '') {
      setReplyError('replyRequired');
      return;
    }
    const version = currentView.conversation.version;
    try {
      const updated = await reply(conversationId, replyDraft, version, organizationId);
      setCurrentView(updated);
      setReplyDraft('');
      setReplyError(undefined);
      setStatus(messagesText(locale, 'replySent'));
    } catch (failure: unknown) {
      // The draft deliberately stays in component state: a conflict must never
      // discard what the staff member typed, and nothing is persisted locally.
      setCommandError(asMessagesTransportError(failure));
    }
  }

  function handleAction(action: ConfirmableAction): void {
    setConfirm({ action, subject: '', reason: '', error: undefined });
  }

  function needsSubject(action: ConfirmableAction): boolean {
    return action === 'assign' || action === 'transfer';
  }

  async function handleConfirm(active: ConfirmState): Promise<void> {
    if (needsSubject(active.action) && active.subject.trim() === '') {
      setConfirm({ ...active, error: 'subjectRequired' });
      return;
    }
    if (active.reason.trim() === '') {
      setConfirm({ ...active, error: 'reasonRequired' });
      return;
    }
    const version = currentView.conversation.version;
    let result: Promise<Conversation>;
    if (active.action === 'assign') {
      result = assign(conversationId, active.subject, active.reason, version, organizationId);
    } else if (active.action === 'transfer') {
      result = transfer(conversationId, active.subject, active.reason, version, organizationId);
    } else if (active.action === 'resolve') {
      result = resolve(conversationId, active.reason, version, organizationId);
    } else {
      result = close(conversationId, active.reason, version, organizationId);
    }
    try {
      const updated = await result;
      setCurrentView((current) => ({ ...current, conversation: updated }));
      setConfirm(undefined);
      setStatus(messagesText(locale, STATUS_KEY[active.action]));
    } catch (failure: unknown) {
      // Keep the dialog and its typed values open on a stale-version conflict.
      setCommandError(asMessagesTransportError(failure));
    }
  }

  const subjectError =
    confirm?.error === 'subjectRequired' ? messagesText(locale, 'subjectRequired') : undefined;
  const reasonError =
    confirm?.error === 'reasonRequired' ? messagesText(locale, 'reasonRequired') : undefined;

  return (
    <div>
      <ConversationScreen
        view={currentView}
        replyDraft={replyDraft}
        replyError={replyError === undefined ? undefined : messagesText(locale, replyError)}
        commandError={commandError}
        status={status}
        onReplyDraftChange={(value) => setReplyDraft(value)}
        onReply={handleReply}
        onAction={handleAction}
      />
      {confirm === undefined ? null : (
        <section
          role="group"
          aria-label={messagesText(locale, TITLE_KEY[confirm.action])}
          style={{
            display: 'grid',
            gap: tokens.spacing.sm,
            border: `1px solid ${tokens.border}`,
            padding: tokens.spacing.sm,
          }}
        >
          <h3>{messagesText(locale, TITLE_KEY[confirm.action])}</h3>
          <Form
            label={messagesText(locale, TITLE_KEY[confirm.action])}
            submitLabel={messagesText(locale, CONFIRM_KEY[confirm.action])}
            onSubmit={() => handleConfirm(confirm)}
          >
            <div>
              {needsSubject(confirm.action) ? (
                <FormField
                  id="confirm-subject"
                  label={messagesText(
                    locale,
                    confirm.action === 'assign' ? 'assigneeSubject' : 'targetSubject',
                  )}
                  value={confirm.subject}
                  onChange={(value) => setConfirm({ ...confirm, subject: value })}
                  {...(subjectError === undefined ? {} : { error: subjectError })}
                />
              ) : null}
              <FormField
                id="confirm-reason"
                label={messagesText(locale, 'reason')}
                value={confirm.reason}
                onChange={(value) => setConfirm({ ...confirm, reason: value })}
                {...(reasonError === undefined ? {} : { error: reasonError })}
              />
            </div>
          </Form>
          <Button
            buttonRef={cancelRef}
            variant="secondary"
            onClick={() => setConfirm(undefined)}
          >
            {messagesText(locale, 'cancel')}
          </Button>
        </section>
      )}
    </div>
  );
}

/** API-backed conversation at `/messages/:id`; generic title, no clinical detail. */
export function ConversationPage() {
  const organizationId = useActiveOrganization();
  const { params } = useRoute();
  const { locale } = useI18n();
  const id = params.id ?? '';

  return (
    <ApiPage
      resource={{
        label: messagesText(locale, 'conversation'),
        load: () => getConversation(id, organizationId),
        deps: [id, organizationId],
      }}
    >
      {(view) => (
        <ConversationContent
          key={`${organizationId}\u0000${id}`}
          view={view}
          conversationId={id}
          organizationId={organizationId}
        />
      )}
    </ApiPage>
  );
}
