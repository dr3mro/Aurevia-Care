import { useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { defined } from '../../components/invariant';
import { StateView } from '../../components/StateViews';
import type { StateViewActions } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { listInbox, recordThreadAction, sendReply } from './api';
import { hasDraftContent, hasReason } from './domain';
import { inboxText } from './labels';
import { InboxScreen } from './InboxScreen';
import type { InboxView, SendDraft, WorkDraft } from './types';

/** Normalise an unexpected command rejection to the typed transport failure. */
function toTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/**
 * Tenant-scoped unit-inbox page (MSG-02, MSG-06, WEB-09). Reply text, the work
 * reason and the failed command live here, so a conflict shows the state view
 * without discarding anything the user typed. Recovery actions are wired, not
 * left as inert defaults.
 */
export function InboxPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const [replyText, setReplyText] = useState('');
  const [workDraft, setWorkDraft] = useState<WorkDraft | undefined>(undefined);
  const [reasonError, setReasonError] = useState<string | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [retry, setRetry] = useState<(() => void) | undefined>(undefined);
  const [recoveryNotice, setRecoveryNotice] = useState<string | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [draftOrganization, setDraftOrganization] = useState(organizationId);

  // A tenant switch clears every draft synchronously during render. The page
  // stays mounted while useApi refetches, so without this a reply or reason
  // typed for one tenant would be shown and sent under the next tenant.
  if (draftOrganization !== organizationId) {
    setDraftOrganization(organizationId);
    setReplyText('');
    setWorkDraft(undefined);
    setReasonError(undefined);
    setCommandError(undefined);
    setRetry(undefined);
    setRecoveryNotice(undefined);
  }

  const reload = (): void => setGeneration((value) => value + 1);

  const resource = {
    label: inboxText(locale, 'title'),
    load: () => listInbox(organizationId),
    deps: [organizationId, generation],
  };

  async function submitReply(draft: SendDraft): Promise<void> {
    if (!hasDraftContent(draft.draft)) {
      return;
    }
    try {
      await sendReply(draft, organizationId);
      setReplyText('');
      setCommandError(undefined);
      setRetry(undefined);
      reload();
    } catch (failure: unknown) {
      setRetry(() => () => {
        void submitReply(draft);
      });
      setCommandError(toTransportError(failure));
    }
  }

  async function submitWork(draft: WorkDraft): Promise<void> {
    if (!hasReason(draft.reason)) {
      setReasonError(inboxText(locale, 'reasonRequired'));
      return;
    }
    try {
      await recordThreadAction(draft, organizationId);
      setWorkDraft(undefined);
      setReasonError(undefined);
      setCommandError(undefined);
      setRetry(undefined);
      reload();
    } catch (failure: unknown) {
      setRetry(() => () => {
        void submitWork(draft);
      });
      setCommandError(toTransportError(failure));
    }
  }

  const actions: StateViewActions = {
    onReload: () => {
      setCommandError(undefined);
      setReasonError(undefined);
      reload();
    },
    onRetry: () => {
      if (retry !== undefined) {
        retry();
      } else {
        reload();
      }
    },
    onCompare: () => {
      setRecoveryNotice(inboxText(locale, 'compareNotice').replace('{reason}', replyText));
    },
    onCopy: () => {
      setRecoveryNotice(inboxText(locale, 'copyNotice').replace('{reason}', replyText));
    },
  };

  return (
    <>
      {recoveryNotice !== undefined ? <p role="status">{recoveryNotice}</p> : null}
      <ApiPage<InboxView> resource={resource} actions={actions} isEmpty={(view) => view.threads.length === 0}>
        {(view) => (
          <div className="page" data-inbox-page data-breakpoint={breakpoint}>
            {commandError !== undefined ? (
              <div data-testid="inbox-command-error">
                {StateView.forTransportError(commandError, actions)}
                <Button onClick={defined(retry, 'failed command retry')}>
                  {inboxText(locale, 'retryCommand')}
                </Button>
              </div>
            ) : null}
            <InboxScreen
              view={view}
              replyText={replyText}
              workDraft={workDraft}
              reasonError={reasonError}
              onReplyTextChange={setReplyText}
              onSend={(draft) => {
                void submitReply(draft);
              }}
              onWorkDraftChange={(next) => {
                setWorkDraft(next);
                setReasonError(undefined);
              }}
              onWorkSubmit={(next) => {
                void submitWork(next);
              }}
              onDiscardDraft={() => setReplyText('')}
            />
          </div>
        )}
      </ApiPage>
    </>
  );
}
