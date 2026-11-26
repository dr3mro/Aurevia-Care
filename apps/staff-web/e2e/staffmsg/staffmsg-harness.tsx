import { StrictMode, useCallback, useEffect, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { ActiveOrganizationProvider } from '../../src/api/activeOrganization';
import { TransportError } from '../../src/api/transport';
import { RouterProvider } from '../../src/app/router';
import { MailPage } from '../../src/features/staffmsg/mail/MailPage';
import type { DirectoryEntry, MailAttachmentView } from '../../src/features/staffmsg/mail/types';
import { MessengerPanel } from '../../src/features/staffmsg/messenger/MessengerPanel';
import type { MessengerPanelProps } from '../../src/features/staffmsg/messenger/MessengerPanel';
import { listConversations, listMessages, listUnreadCounts, sendMessage } from '../../src/features/staffmsg/messenger/api';
import type { StreamCleanup } from '../../src/features/staffmsg/messenger/realtime';
import type {
  ComposerDraft,
  FailedSend,
  MessengerView,
  PendingAttachment,
} from '../../src/features/staffmsg/messenger/types';
import { PatientReferenceChip, ReferenceInserter } from '../../src/features/staffmsg/references';
import type { AccessOutcome } from '../../src/features/staffmsg/references/screening';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import {
  authorSubject,
  cleanDocumentId,
  cleanFileName,
  homeFacilityId,
  instantConversationId,
  organizationId,
  otherFacilityId,
  peerSubject,
  quarantinedDocumentId,
  quarantinedFileName,
  referenceId,
  remoteSubject,
  syntheticPatientId,
} from './synthetic-staffmsg-api';

/**
 * Dev-only harness for the staff-messaging journey (MSG-02, MSG-03, WEB-09).
 *
 * The pre-login shell fixes the role to reception and denies the feature
 * routes, so the real messenger panel, mail page and reference components are
 * mounted here under the same providers the portal uses. A query parameter
 * selects one of three synthetic staff views; all three pages share the one
 * in-memory API stub. Only the harness-owned scan panel and refresh control
 * are new UI: production has no scan-failure component yet, and the routed
 * screens have no external refresh prop.
 */

/** Stable tenant value; hoisted so React sees one identity per mount. */
const tenant = { organizationId };

/** The synthetic identity selected by the harness URL. */
interface ViewIdentity {
  readonly viewer: string;
  readonly facilityId: string;
}

/** Map the URL parameters to the three synthetic staff views. */
function identityFrom(params: URLSearchParams): ViewIdentity {
  const user = params.get('user');
  const viewer = user === 'omar' ? peerSubject : user === 'layla' ? remoteSubject : authorSubject;
  const facilityId = params.get('facility') === otherFacilityId ? otherFacilityId : homeFacilityId;
  return { viewer, facilityId };
}

/** Tenant-scoped directory; the second facility is selectable but marked. */
const directory: readonly DirectoryEntry[] = [
  {
    value: authorSubject,
    label: 'Synthetic Nora',
    kind: 'person',
    organizationId,
    facilityId: homeFacilityId,
    holderValues: [],
  },
  {
    value: peerSubject,
    label: 'Synthetic Omar',
    kind: 'person',
    organizationId,
    facilityId: homeFacilityId,
    holderValues: [],
  },
  {
    value: remoteSubject,
    label: 'Synthetic Layla',
    kind: 'person',
    organizationId,
    facilityId: otherFacilityId,
    holderValues: [],
  },
];

/** Clean and quarantined files offered to both real composers. */
const stagedFiles: readonly MailAttachmentView[] = [
  { documentId: cleanDocumentId, fileName: cleanFileName },
  { documentId: quarantinedDocumentId, fileName: quarantinedFileName },
];

/** Pending attachments derived from the same two document handles. */
const pendingAttachments: readonly PendingAttachment[] = [
  { id: cleanDocumentId, name: cleanFileName },
  { id: quarantinedDocumentId, name: quarantinedFileName },
];

/** No work is scheduled by the harness-owned realtime deps. */
const noopCleanup: StreamCleanup = { cancel: () => undefined };
const openStream = () => noopCleanup;
const poll = () => Promise.resolve({ events: [], nextCursor: undefined });
const schedule = () => noopCleanup;

/** Post one JSON command with the tenant and CSRF headers the stub requires. */
function post(url: string, body: Record<string, unknown>): Promise<Response> {
  return fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Active-Organization': organizationId,
      'X-Correlation-Id': 'synthetic-correlation-staffmsg',
      'X-XSRF-TOKEN': 'synthetic-csrf-token',
      'Idempotency-Key': 'synthetic-idempotency-staffmsg',
    },
    body: JSON.stringify(body),
  });
}

/** Harness-only copy, kept separate from the translated production labels. */
const harnessWords = {
  en: {
    refresh: 'Refresh views',
    clean: 'Clean',
    failed: 'Quarantined — scan failed',
    remove: 'Remove file',
    replace: 'Replace with clean file',
    subject: 'Synthetic follow-up',
    reference: 'Reference for this view',
  },
  ar: {
    refresh: 'تحديث العرض',
    clean: 'نظيفة',
    failed: 'محجوبة — فحص غير ناجح',
    remove: 'إزالة الملف',
    replace: 'استبدال بملف نظيف',
    subject: 'متابعة توضيحية',
    reference: 'مرجع لهذا العرض',
  },
} as const;

/** Drive the real messenger panel with data read through its real API client. */
function MessengerDriver({
  viewer,
  facilityId,
  refreshToken,
  availableAttachments,
}: {
  readonly viewer: string;
  readonly facilityId: string;
  readonly refreshToken: number;
  readonly availableAttachments: readonly PendingAttachment[];
}): ReactElement {
  const [loadState, setLoadState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [view, setView] = useState<MessengerView>({
    facilityId,
    conversations: [],
    messages: [],
    selectedConversationId: instantConversationId,
  });
  const [draft, setDraft] = useState<ComposerDraft>({ text: '', attachments: [] });
  const [sendFailure, setSendFailure] = useState<FailedSend | undefined>(undefined);

  /** Read one projection through the real typed API client, with no state write. */
  const read = useCallback(async (): Promise<MessengerView> => {
    const [conversations, messages, unread] = await Promise.all([
      listConversations(organizationId, facilityId),
      listMessages(organizationId, facilityId, instantConversationId, viewer),
      listUnreadCounts(organizationId, facilityId),
    ]);
    const count = unread.find((entry) => entry.conversationId === instantConversationId)?.unreadCount ?? 0;
    return {
      facilityId,
      conversations: conversations.map((entry) => ({ ...entry, unreadCount: count })),
      messages,
      selectedConversationId: instantConversationId,
    };
  }, [facilityId, viewer]);

  /** Publish one read into the panel. */
  const apply = useCallback((next: MessengerView): void => {
    setView(next);
    setLoadState('ready');
  }, []);

  /** Refresh on demand after a publish or a harness refresh click. */
  const refresh = useCallback(async (): Promise<void> => {
    try {
      apply(await read());
    } catch {
      setLoadState('error');
    }
  }, [apply, read]);

  // Load the initial view from the boundary and ignore late results from a
  // previous identity, exactly as the routed screens do on tenant switches.
  useEffect(() => {
    let active = true;
    void read()
      .then((next) => {
        if (active) {
          apply(next);
        }
      })
      .catch(() => {
        if (active) {
          setLoadState('error');
        }
      });
    return () => {
      active = false;
    };
  }, [apply, read, refreshToken]);

  const publish = async (text: string, files: readonly PendingAttachment[]): Promise<void> => {
    // Named files pass the scan gate before the message is published, so a
    // quarantined file can never produce a sent message or a storage fact.
    const scanned = await post('/api/v1/staff/attachments/send', {
      documentIds: files.map((file) => ({ value: file.id })),
    });
    if (!scanned.ok) {
      setSendFailure({
        error: new TransportError('conflict', {
          correlationId: 'synthetic-correlation-staffmsg',
          code: 'QUARANTINED_FILE',
          retryAfterSeconds: undefined,
        }),
        text,
        files,
        idempotencyKey: 'synthetic-instant-send',
      });
      return;
    }
    await sendMessage(
      organizationId,
      { facilityId, conversationId: instantConversationId, body: text },
      'synthetic-instant-send',
      viewer,
    );
    setDraft({ text: '', attachments: [] });
    setSendFailure(undefined);
    await refresh();
  };

  const props: MessengerPanelProps = {
    view,
    organizationId,
    facilityId,
    selfSubject: viewer,
    draft,
    stagedFiles: [...availableAttachments],
    loadState,
    error: undefined,
    sendFailure,
    openStream,
    poll,
    schedule,
    onRefresh: () => void refresh(),
    onSelectConversation: () => undefined,
    onDraftChange: (patch) => setDraft((current) => ({ ...current, ...patch })),
    onStageFile: (file) =>
      setDraft((current) => ({ ...current, attachments: [...current.attachments, file] })),
    onRemoveFile: (id) =>
      setDraft((current) => ({
        ...current,
        attachments: current.attachments.filter((file) => file.id !== id),
      })),
    onSend: (text, files) => void publish(text, files),
    onRetry: () => undefined,
    onReload: () => void refresh(),
    onSignIn: () => undefined,
    onCompare: () => undefined,
    onCopy: () => undefined,
  };
  return <MessengerPanel {...props} />;
}

/** Mount the real mail page with a keyed remount as the refresh path. */
function MailWorkspace({
  viewer,
  facilityId,
  refreshToken,
  availableFiles,
}: {
  readonly viewer: string;
  readonly facilityId: string;
  readonly refreshToken: number;
  readonly availableFiles: readonly MailAttachmentView[];
}): ReactElement {
  return (
    <MailPage
      key={`mail-${refreshToken}`}
      directory={directory}
      actor={{ organizationId, facilityId }}
      crossFacilityEnabled
      stagedFiles={[...availableFiles]}
      selfValue={viewer}
      facilityId={facilityId}
    />
  );
}

/** Insert a reference and render its label-only view and fresh access result. */
function ReferenceWorkspace(): ReactElement {
  const { locale } = useI18n();
  const [outcome, setOutcome] = useState<AccessOutcome | undefined>(undefined);
  const [label, setLabel] = useState('');

  const insert = async (subject: string): Promise<void> => {
    const attached = await post('/api/v1/staff/references/attach', {
      targetId: syntheticPatientId,
      connectionId: 'synthetic-connection',
      kind: 'patient',
      label: 'Synthetic chart reference',
      subject,
    });
    if (!attached.ok) {
      return;
    }
    // The published view route carries its opaque handles in the query; the
    // stub deliberately mirrors that contract and returns label-only data.
    const view = await fetch(
      `/api/v1/staff/references/view?id=${encodeURIComponent(referenceId)}&connectionId=synthetic-connection&kind=patient&targetId=${encodeURIComponent(syntheticPatientId)}&label=Synthetic%20chart%20reference`,
      { headers: { 'X-Active-Organization': organizationId } },
    );
    const projection = (await view.json()) as { label?: string };
    const opened = await post('/api/v1/staff/references/open', { id: referenceId });
    const result = (await opened.json()) as { outcome?: string };
    setLabel(projection.label ?? 'Synthetic chart reference');
    setOutcome(result.outcome === 'ALLOWED' ? 'ALLOWED' : 'NO_RELATIONSHIP');
  };

  return (
    <section aria-label={harnessWords[locale].reference} data-testid="staffmsg-reference">
      <h2>{harnessWords[locale].reference}</h2>
      <ReferenceInserter onInsert={(subject) => void insert(subject)} />
      {outcome === undefined ? null : (
        <PatientReferenceChip label={label} patientId={syntheticPatientId} access={outcome} />
      )}
    </section>
  );
}

/** One pending-file row with its scan verdict and harness-owned recovery. */
function ScanRow({
  verdict,
  onRemove,
  onReplace,
}: {
  readonly verdict: { readonly id: string; readonly fileName: string; readonly verdict: string };
  readonly onRemove: (id: string) => void;
  readonly onReplace: (id: string) => void;
}): ReactElement {
  const { locale } = useI18n();
  const failed = verdict.verdict !== 'clean';
  return (
    <li data-testid={`scan-result-${verdict.id}`} data-verdict={verdict.verdict}>
      <bdi>{verdict.fileName}</bdi>: {failed ? harnessWords[locale].failed : harnessWords[locale].clean}
      {failed ? (
        <span className="cluster">
          <button type="button" onClick={() => onRemove(verdict.id)}>
            {harnessWords[locale].remove}
          </button>
          <button type="button" onClick={() => onReplace(verdict.id)}>
            {harnessWords[locale].replace}
          </button>
        </span>
      ) : null}
    </li>
  );
}

/** Show the independent scan verdicts and let the sender remove or replace. */
function ScanWorkspace({
  onRemove,
  onReplace,
}: {
  readonly onRemove: (id: string) => void;
  readonly onReplace: (id: string) => void;
}): ReactElement {
  const [files, setFiles] = useState<
    readonly { readonly id: string; readonly fileName: string; readonly verdict: string }[]
  >([]);

  useEffect(() => {
    const load = async (): Promise<void> => {
      const response = await fetch('/api/v1/staff/attachments/pending?draftId=synthetic-draft-instant', {
        headers: { 'X-Active-Organization': organizationId },
      });
      const body = (await response.json()) as readonly {
        readonly id: string;
        readonly fileName: string;
        readonly verdict: string;
      }[];
      setFiles(body);
    };
    void load();
  }, []);

  return (
    <section aria-label="Scan results" data-testid="staffmsg-scan">
      <ul>
        {files.map((file) => (
          <ScanRow key={file.id} verdict={file} onRemove={onRemove} onReplace={onReplace} />
        ))}
      </ul>
    </section>
  );
}

/** Compose the three real surfaces plus the harness-only controls. */
function Workstation({ identity }: { readonly identity: ViewIdentity }): ReactElement {
  const { locale, toggleLocale } = useI18n();
  const [refreshToken, setRefreshToken] = useState(0);
  const [quarantinedRemoved, setQuarantinedRemoved] = useState(false);
  const availableFiles = quarantinedRemoved
    ? stagedFiles.filter((file) => file.documentId !== quarantinedDocumentId)
    : stagedFiles;
  const availableAttachments = quarantinedRemoved
    ? pendingAttachments.filter((file) => file.id !== quarantinedDocumentId)
    : pendingAttachments;
  const words = harnessWords[locale];

  return (
    <div style={{ display: 'grid', gap: '1rem' }}>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <button type="button" data-testid="staffmsg-refresh" onClick={() => setRefreshToken((value) => value + 1)}>
        {words.refresh}
      </button>
      <output data-testid="staffmsg-identity">{`${identity.viewer}:${identity.facilityId}`}</output>
      <MessengerDriver
        viewer={identity.viewer}
        facilityId={identity.facilityId}
        refreshToken={refreshToken}
        availableAttachments={availableAttachments}
      />
      <MailWorkspace
        viewer={identity.viewer}
        facilityId={identity.facilityId}
        refreshToken={refreshToken}
        availableFiles={availableFiles}
      />
      <ReferenceWorkspace />
      <ScanWorkspace
        onRemove={() => setQuarantinedRemoved(true)}
        onReplace={() => setQuarantinedRemoved(true)}
      />
      {quarantinedRemoved ? <output data-testid="staffmsg-quarantine-removed">{words.clean}</output> : null}
      <output data-testid="staffmsg-available-attachments">
        {availableAttachments.map((file) => file.name).join(', ')}
      </output>
    </div>
  );
}

/** Mount the harness tree with the providers the staff portal uses. */
function Harness(): ReactElement {
  const identity = identityFrom(new URLSearchParams(window.location.search));
  return (
    <StrictMode>
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <ActiveOrganizationProvider value={tenant}>
            <RouterProvider patterns={['/patients/:id']} initialPath="/">
              <Workstation identity={identity} />
            </RouterProvider>
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>
    </StrictMode>
  );
}

// The dev-only HTML supplies the container; the type guard keeps the mount
// safe without an assertion or an unreachable error branch.
const container = document.getElementById('staffmsg-harness');
if (container instanceof HTMLElement) {
  createRoot(container).render(<Harness />);
}
