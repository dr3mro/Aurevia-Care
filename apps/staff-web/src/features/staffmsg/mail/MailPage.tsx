import { useLayoutEffect, useRef, useState } from 'react';
import { useActiveOrganization } from '../../../api/activeOrganization';
import type { TransportError } from '../../../api/transport';
import { ApiPage } from '../../../components/ApiPage';
import { useSession } from '../../../components/SessionContext';
import type { StateViewActions } from '../../../components/StateViews';
import { useI18n } from '../../../i18n/I18nProvider';
import { loadMailOverview } from './api';
import { alphaActor, alphaDirectory, emptyDraft, homeFacilityId, selfSubject, stagedFiles } from './fixtures';
import { mailText } from './labels';
import {
  addRecipient,
  attach,
  confirmSend,
  removeRecipient,
  startCompose,
  submitSend,
  updateDraft,
} from './mailCommands';
import type { AutosaveState, MailCommandContext } from './mailCommands';
import { MailNotices } from './MailNotices';
import { MailScreen } from './MailScreen';
import type { MailConfirmation } from './MailScreen';
import type {
  ComposeDraft,
  ComposeMode,
  DirectoryEntry,
  MailActor,
  MailAttachmentView,
  MailMessageView,
  MailOverviewView,
} from './types';

/** Props injected by the owning shell; defaults keep the slice self-contained. */
export interface MailPageProps {
  readonly directory?: readonly DirectoryEntry[] | undefined;
  readonly actor?: MailActor | undefined;
  readonly crossFacilityEnabled?: boolean | undefined;
  readonly stagedFiles?: readonly MailAttachmentView[] | undefined;
  readonly selfValue?: string | undefined;
  readonly facilityId?: string | undefined;
}

/**
 * Tenant-scoped staff mail page (MSG-02, MSG-03, WEB-09).
 *
 * The versioned draft, command error, confirmation payload and recovery state
 * live here so a stale response never discards typed work. Autosave creates the
 * private draft on first content and then saves each version with If-Match; a
 * 412 keeps the editor mounted and offers compare, reload and copy. A 428 is
 * intercepted before the shared state view and becomes an explicit recipient
 * confirmation that reuses one idempotency key. Switching tenant clears every
 * draft synchronously before the replacement request can settle.
 */
export function MailPage({
  directory = alphaDirectory,
  actor = alphaActor,
  crossFacilityEnabled = false,
  stagedFiles: staged = stagedFiles,
  selfValue = selfSubject,
  facilityId = homeFacilityId,
}: MailPageProps) {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const session = useSession();
  const [draft, setDraft] = useState<ComposeDraft>(emptyDraft);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [retry, setRetry] = useState<(() => void) | undefined>(undefined);
  const [confirmation, setConfirmation] = useState<MailConfirmation | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [comparing, setComparing] = useState(false);
  const [copied, setCopied] = useState<ComposeDraft | undefined>(undefined);
  const [generation, setGeneration] = useState(0);
  const [draftOrganization, setDraftOrganization] = useState(organizationId);
  const [sendKey, setSendKey] = useState<string>(() => crypto.randomUUID());
  const activeOrganization = useRef(organizationId);
  const autosaveState = useRef<AutosaveState>({ inFlight: false, sequence: 0, latest: undefined });

  // Clear every tenant-owned value during render: the page stays mounted while
  // the replacement request runs, so a late result cannot paint the old tenant.
  if (draftOrganization !== organizationId) {
    setDraftOrganization(organizationId);
    setDraft(emptyDraft);
    setCommandError(undefined);
    setRetry(undefined);
    setConfirmation(undefined);
    setNotice(undefined);
    setComparing(false);
    setCopied(undefined);
    setSendKey(crypto.randomUUID());
  }

  useLayoutEffect(() => {
    activeOrganization.current = organizationId;
    // Detach any in-flight autosave closure so it cannot requeue this tenant's
    // typed work under the organization it started with.
    autosaveState.current = { inFlight: false, sequence: 0, latest: undefined };
  }, [organizationId]);

  function reload(): void {
    setGeneration((value) => value + 1);
  }

  function finishSend(count: number): void {
    setDraft(emptyDraft);
    setConfirmation(undefined);
    setCommandError(undefined);
    setRetry(undefined);
    setNotice(mailText(locale, 'sentStatus').replace('{count}', String(count)));
    setSendKey(crypto.randomUUID());
    reload();
  }

  const ctx: MailCommandContext = {
    organizationId,
    locale,
    directory,
    selfValue,
    activeOrganization,
    autosaveState,
    setDraft,
    setCommandError,
    setRetry,
    setNotice,
    setConfirmation,
    setSendKey,
    finishSend,
  };

  const actions: StateViewActions & { onRetry: () => void } = {
    onReload: () => {
      setCommandError(undefined);
      setComparing(false);
      reload();
    },
    onRetry: () => {
      if (retry !== undefined) {
        retry();
      } else {
        reload();
      }
    },
    onCompare: () => setComparing(true),
    onCopy: () => setCopied(draft),
    onSignIn: session.onSignIn,
  };

  const resource = {
    label: mailText(locale, 'title'),
    load: () => loadMailOverview(organizationId, facilityId),
    deps: [organizationId, generation, facilityId],
  };

  return (
    <ApiPage<MailOverviewView> resource={resource} actions={actions}>
      {(view) => (
        <div className="page" data-mail-page>
          <MailNotices
            locale={locale}
            notice={notice}
            error={commandError}
            actions={actions}
            comparing={comparing}
            copied={copied}
            draft={draft}
            view={view}
          />
          <MailScreen
            view={view}
            directory={directory}
            actor={actor}
            crossFacilityEnabled={crossFacilityEnabled}
            selfValue={selfValue}
            draft={draft}
            stagedFiles={staged}
            confirmation={confirmation}
            onDraftChange={(patch) => updateDraft(ctx, { ...draft, ...patch })}
            onAddRecipient={(field, entry) => addRecipient(ctx, draft, field, entry)}
            onRemoveRecipient={(field, value) => removeRecipient(ctx, draft, field, value)}
            onAttach={(file) => attach(ctx, draft, file)}
            onStartCompose={(mode: ComposeMode, message?: MailMessageView) =>
              startCompose(ctx, mode, message, emptyDraft)
            }
            onSend={() => {
              void submitSend(ctx, draft, sendKey);
            }}
            onConfirm={() => {
              void confirmSend(ctx, draft, confirmation, sendKey);
            }}
            onCancelConfirm={() => setConfirmation(undefined)}
          />
        </div>
      )}
    </ApiPage>
  );
}
