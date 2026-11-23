import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import type { TransportErrorKind } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { useRoute } from '../../app/router';
import { Button } from '../../components/Button';
import { ConflictState, LoadingState, StateView } from '../../components/StateViews';
import { defined } from '../../components/invariant';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import {
  amendNote,
  getNote,
  getPatient,
  markEnteredInError,
  signAddendum,
  signNote,
  soapToContent,
  toNoteRevision,
  updateNote,
} from './api';
import type { ClinicalNoteRevisionResponse, NoteViewResponse, PatientViewResponse } from './api';
import type { NoteRevision, SoapContent } from './domain';
import { NoteEditor } from './NoteEditor';
import { chartText } from './messages';

/** One resolved note together with the patient that owns it. */
interface NotePageData {
  patient: PatientViewResponse;
  noteView: NoteViewResponse;
}

/** Normalise any command rejection to the typed error the state views present. */
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

/** A stale version is either a 409 conflict or a missing/stale If-Match. */
function isConflict(kind: TransportErrorKind): boolean {
  return kind === 'conflict' || kind === 'precondition-required';
}

/**
 * Labelled confirmation surface with native focus order. The shared modal
 * placeholder traps every Tab on its close button, which makes a required
 * confirmation action unreachable by keyboard; clinical confirmations render
 * in flow instead, with the safe Cancel action focused first but every control
 * still Tab-reachable (WEB-09, accessibility patterns 3.3.4).
 */
function ConfirmationDialog({
  title,
  closeLabel,
  onClose,
  children,
}: {
  title: string;
  closeLabel: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const compact = breakpoint === 'compact';
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  // responsive.css has no sticky action-row class, so the safe action stays
  // reachable above the on-screen keyboard with logical inline properties. The
  // logical inset and symmetric padding mirror under RTL without a second rule.
  const stickyActions = compact
    ? {
        position: 'sticky' as const,
        insetBlockEnd: '0px',
        zIndex: 1,
        background: tokens.background,
        paddingBlock: tokens.spacing.sm,
        borderBlockStart: `2px solid ${tokens.border}`,
      }
    : undefined;

  return (
    <section
      role="dialog"
      aria-labelledby="note-confirmation-title"
      className="stack"
      data-breakpoint={breakpoint}
      data-note-confirmation-view={compact ? 'sheet-actions' : 'inline-actions'}
      style={{
        border: `2px solid ${tokens.border}`,
        padding: tokens.spacing.lg,
      }}
    >
      <h2 id="note-confirmation-title">{title}</h2>
      <div className="stack">{children}</div>
      <div data-note-confirm-actions={breakpoint} style={stickyActions}>
        <Button buttonRef={closeRef} variant="secondary" onClick={onClose}>
          {closeLabel}
        </Button>
      </div>
    </section>
  );
}

/** A browser-level guard asks before leaving with unsaved draft text. */
function useUnsavedGuard(dirty: boolean): void {
  useEffect(() => {
    if (!dirty) {
      return undefined;
    }
    const warn = (event: BeforeUnloadEvent): void => {
      event.preventDefault();
      // Legacy browsers still require the returnValue assignment.
      Reflect.set(event, 'returnValue', '');
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);
}

/** The authoritative load result the workflow hook reads from. */
type NoteResult = ReturnType<typeof useApi<NotePageData>>;

/** Everything the asynchronous note commands read and the state they update. */
interface NoteCommandContext {
  noteId: string;
  organizationId: string;
  locale: Locale;
  draft: NoteRevision | undefined;
  source: ClinicalNoteRevisionResponse | undefined;
  reason: string;
  result: NoteResult;
  recordFailure: (failure: unknown) => void;
  setDirty: (value: boolean) => void;
  setConflict: (value: boolean) => void;
  setCommandError: (value: TransportError | undefined) => void;
  setStatus: (value: string) => void;
  setConfirm: (value: 'sign' | 'entered-error' | undefined) => void;
  setReason: (value: string) => void;
  setSignedAttestation: (value: string | null) => void;
}

/** The save, sign, amend and entered-in-error commands for the loaded note. */
function useNoteCommands(ctx: NoteCommandContext) {
  const { noteId, organizationId, locale, draft, source, reason, result, recordFailure } = ctx;
  const { setDirty, setConflict, setCommandError, setStatus, setConfirm } = ctx;
  const { setReason, setSignedAttestation } = ctx;

  async function handleSave(): Promise<void> {
    // The save action only renders once the authoritative revision is loaded.
    const current = defined(draft, 'note draft');
    const currentSource = defined(source, 'note source');
    setStatus(chartText(locale, 'saving'));
    try {
      await updateNote(
        noteId,
        current.version,
        { title: currentSource.title, content: soapToContent(current.content) },
        organizationId,
      );
      setDirty(false);
      setConflict(false);
      setCommandError(undefined);
      setStatus(chartText(locale, 'savedNotice'));
      result.reload();
    } catch (failure: unknown) {
      recordFailure(failure);
    }
  }

  async function handleSign(): Promise<void> {
    const current = defined(draft, 'note draft');
    const attestation = chartText(locale, 'attestation');
    try {
      const signed =
        current.kind === 'amendment'
          ? await signAddendum(noteId, current.version, attestation, organizationId)
          : await signNote(noteId, current.version, attestation, organizationId);
      setSignedAttestation(signed.attestation);
      setConfirm(undefined);
      setDirty(false);
      setConflict(false);
      setCommandError(undefined);
      setStatus(chartText(locale, 'signedNotice'));
      result.reload();
    } catch (failure: unknown) {
      // Close the confirmation so the conflict/error recovery is the active
      // surface; leaving an aria-modal dialog open would bury its actions.
      setConfirm(undefined);
      recordFailure(failure);
    }
  }

  async function handleAmend(): Promise<void> {
    const current = defined(draft, 'note draft');
    try {
      await amendNote(noteId, current.version, organizationId);
      setCommandError(undefined);
      setStatus(chartText(locale, 'amendNotice'));
      result.reload();
    } catch (failure: unknown) {
      recordFailure(failure);
    }
  }

  async function handleEnteredInError(): Promise<void> {
    const current = defined(draft, 'note draft');
    const currentSource = defined(source, 'note source');
    try {
      await markEnteredInError(
        noteId,
        current.version,
        { reason, affectedRecipients: [currentSource.dataEntrySubject] },
        organizationId,
      );
      setConfirm(undefined);
      setReason('');
      setCommandError(undefined);
      setStatus(chartText(locale, 'enterErrorNotice'));
      result.reload();
    } catch (failure: unknown) {
      setConfirm(undefined);
      recordFailure(failure);
    }
  }

  return { handleSave, handleSign, handleAmend, handleEnteredInError };
}

/** Draft text, workflow flags and the commands that act on the loaded note. */
function useNoteWorkflow(
  noteId: string,
  organizationId: string,
  locale: Locale,
  result: NoteResult,
) {
  const [draft, setDraft] = useState<NoteRevision | undefined>(undefined);
  const [source, setSource] = useState<ClinicalNoteRevisionResponse | undefined>(undefined);
  const [history, setHistory] = useState<readonly NoteRevision[]>([]);
  const [dirty, setDirty] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [comparing, setComparing] = useState(false);
  const [copied, setCopied] = useState(false);
  const [status, setStatus] = useState('');
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [confirm, setConfirm] = useState<'sign' | 'entered-error' | undefined>(undefined);
  const [reason, setReason] = useState('');
  const [signedAttestation, setSignedAttestation] = useState<string | null>(null);
  const [appliedData, setAppliedData] = useState<NotePageData | undefined>(undefined);

  // Initialise (or re-initialise after a command reload) from the authoritative
  // revision while rendering. Typing does not re-run this because the result
  // object is stable until the next load resolves.
  if (result.status === 'ready' && result.data !== appliedData) {
    setAppliedData(result.data);
    setDraft(toNoteRevision(result.data.noteView.current, organizationId));
    setHistory(result.data.noteView.history.map((item) => toNoteRevision(item, organizationId)));
    setSource(result.data.noteView.current);
    setDirty(false);
    setConflict(false);
    setComparing(false);
  }

  useUnsavedGuard(dirty);

  function recordFailure(failure: unknown): void {
    const error = toTransportError(failure);
    setCommandError(error);
    setConflict(isConflict(error.kind));
    setStatus('');
  }

  function handleContentChange(field: keyof SoapContent, value: string): void {
    const current = defined(draft, 'note draft');
    setDraft({ ...current, content: { ...current.content, [field]: value } });
    setDirty(true);
    setStatus('');
  }

  const commands = useNoteCommands({
    noteId,
    organizationId,
    locale,
    draft,
    source,
    reason,
    result,
    recordFailure,
    setDirty,
    setConflict,
    setCommandError,
    setStatus,
    setConfirm,
    setReason,
    setSignedAttestation,
  });

  function handleReload(): void {
    setConflict(false);
    setCommandError(undefined);
    setComparing(false);
    setCopied(false);
    setStatus('');
    result.reload();
  }

  function handleCopy(): void {
    const current = defined(draft, 'note draft');
    if ('clipboard' in navigator) {
      void navigator.clipboard.writeText(soapToContent(current.content));
    }
    setCopied(true);
  }

  return {
    draft,
    source,
    history,
    dirty,
    conflict,
    comparing,
    copied,
    status,
    commandError,
    confirm,
    reason,
    signedAttestation,
    setComparing,
    setConfirm,
    setReason,
    setStatus,
    handleContentChange,
    ...commands,
    handleReload,
    handleCopy,
  };
}

type NoteWorkflow = ReturnType<typeof useNoteWorkflow>;

/** Status lines, attestation, command error, conflict recovery and compare view. */
function NoteNotices({
  workflow,
  draft,
  source,
  locale,
}: {
  workflow: NoteWorkflow;
  draft: NoteRevision;
  source: ClinicalNoteRevisionResponse | undefined;
  locale: Locale;
}) {
  const { status, dirty, signedAttestation, commandError, conflict, copied, comparing } = workflow;
  return (
    <>
      {status !== '' ? (
        <p role="status" data-testid="note-status">
          {status}
        </p>
      ) : null}
      {dirty ? (
        <p role="status" data-testid="unsaved-changes">
          {chartText(locale, 'unsavedChanges')}
        </p>
      ) : null}
      {signedAttestation !== null ? (
        <p data-testid="signer-attestation">
          <b>{chartText(locale, 'signerAttestation')}:</b> {signedAttestation}
        </p>
      ) : null}
      {commandError !== undefined ? (
        conflict ? (
          <ConflictState
            onCompare={() => workflow.setComparing(true)}
            onReload={workflow.handleReload}
            onCopy={workflow.handleCopy}
          />
        ) : (
          StateView.forTransportError(commandError)
        )
      ) : null}
      {copied ? <p>{chartText(locale, 'copiedNotice')}</p> : null}
      {comparing ? (
        <section data-testid="note-compare" className="stack">
          <h3>{chartText(locale, 'compareTitle')}</h3>
          <b>{chartText(locale, 'serverContent')}</b>
          <pre style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>{source?.content}</pre>
          <b>{chartText(locale, 'yourContent')}</b>
          <pre style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>
            {soapToContent(draft.content)}
          </pre>
        </section>
      ) : null}
    </>
  );
}

/** The sign and entered-in-error confirmation dialogs. */
function NoteConfirmations({
  workflow,
  draft,
  source,
  locale,
}: {
  workflow: NoteWorkflow;
  draft: NoteRevision;
  source: ClinicalNoteRevisionResponse | undefined;
  locale: Locale;
}) {
  const { confirm, reason } = workflow;
  const title = (
    <p>
      <MixedScriptText text={defined(source, 'note source').title} lang={locale} /> ·{' '}
      {chartText(locale, 'revision')} {draft.version}
    </p>
  );
  if (confirm === 'sign') {
    return (
      <ConfirmationDialog
        title={chartText(locale, 'signConfirmTitle')}
        closeLabel={chartText(locale, 'cancel')}
        onClose={() => workflow.setConfirm(undefined)}
      >
        {title}
        <p>{chartText(locale, 'signEffect')}</p>
        <p>
          <b>{chartText(locale, 'signerAttestation')}:</b> {chartText(locale, 'attestation')}
        </p>
        <Button onClick={() => void workflow.handleSign()}>{chartText(locale, 'sign')}</Button>
      </ConfirmationDialog>
    );
  }
  if (confirm === 'entered-error') {
    return (
      <ConfirmationDialog
        title={chartText(locale, 'markEnteredInError')}
        closeLabel={chartText(locale, 'cancel')}
        onClose={() => workflow.setConfirm(undefined)}
      >
        {title}
        <p>{chartText(locale, 'enterErrorEffect')}</p>
        <label htmlFor="chart-enter-error-reason">{chartText(locale, 'enterErrorReason')}</label>
        <input
          id="chart-enter-error-reason"
          className="field touch"
          value={reason}
          onChange={(event) => workflow.setReason(event.target.value)}
        />
        <Button
          disabled={reason.trim().length === 0}
          onClick={() => void workflow.handleEnteredInError()}
        >
          {chartText(locale, 'markEnteredInError')}
        </Button>
      </ConfirmationDialog>
    );
  }
  return null;
}

/**
 * API-backed note editor for `/patients/:id/notes/:noteId`.
 *
 * Draft text lives only in component state — never in browser storage. A stale
 * save leaves the typed text intact and offers compare, reload and copy. Signing
 * and entered-in-error are irreversible, so each asks for explicit confirmation
 * that names the note and its effect.
 *
 * The `.page` grid and wrapping clusters keep every clinical field readable from
 * 320 px upward under either direction; the save action and confirmation actions
 * stick to the block end on phones so they stay reachable above the on-screen
 * keyboard. Breakpoint changes reflow layout without remounting the editor, so
 * typed work survives rotation (WEB-07, WEB-09, UX-01).
 */
export function NotePage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const { params } = useRoute();
  const patientId = params.id ?? '';
  const noteId = params.noteId ?? '';

  const result = useApi<NotePageData>(
    () =>
      Promise.all([getPatient(patientId, organizationId), getNote(noteId, organizationId)]).then(
        ([patient, noteView]) => ({ patient, noteView }),
      ),
    [patientId, noteId, organizationId],
  );
  const workflow = useNoteWorkflow(noteId, organizationId, locale, result);
  const { draft, source, history, dirty } = workflow;

  if (draft === undefined) {
    if (result.status === 'error') {
      return <>{StateView.forTransportError(result.error)}</>;
    }
    return <LoadingState />;
  }

  const patientName: string = displayLocalName(
    { latinName: result.status === 'ready' ? result.data.patient.patient.displayName : patientId },
    locale,
  );

  // The .page grid supplies logical safe-area padding and the 1200px reading
  // cap. The patient identity is a wrapping cluster and the save action is a
  // sticky bar on phones so it stays reachable above the on-screen keyboard
  // (WEB-09); responsive.css has no sticky-action class, so use logical insets.
  const stickySave =
    breakpoint === 'compact'
      ? {
          position: 'sticky' as const,
          insetBlockEnd: '0px',
          zIndex: 1,
          background: tokens.background,
          paddingBlock: tokens.spacing.sm,
          borderBlockStart: `2px solid ${tokens.border}`,
        }
      : undefined;

  return (
    <div
      className="page"
      data-breakpoint={breakpoint}
      data-note-page-view={breakpoint === 'compact' ? 'sticky-save-action' : 'inline-save-action'}
      style={{ color: tokens.foreground }}
    >
      {result.status === 'error' ? (
        <div data-testid="note-page-load-error">{StateView.forTransportError(result.error)}</div>
      ) : null}
      <header className="stack">
        <h2>{chartText(locale, 'clinicalNote')}</h2>
        <p className="cluster" style={{ marginBlock: 0, overflowWrap: 'anywhere' }}>
          <b>{chartText(locale, 'patient')}:</b>{' '}
          <MixedScriptText text={patientName} lang={locale} />
          {' · '}
          <b>{chartText(locale, 'patientId')}:</b> <code dir="ltr">{patientId}</code>
        </p>
      </header>

      <NoteNotices workflow={workflow} draft={draft} source={source} locale={locale} />

      <NoteEditor
        key={draft.id}
        revision={draft}
        history={history}
        locale={locale}
        onContentChange={workflow.handleContentChange}
        onSign={() => {
          // The sign command carries no edited content: signing while dirty
          // would silently certify the last saved revision, not what is shown.
          if (dirty) {
            workflow.setStatus(chartText(locale, 'saveBeforeSign'));
            return;
          }
          workflow.setConfirm('sign');
        }}
        onAmend={() => void workflow.handleAmend()}
        onMarkEnteredInError={() => workflow.setConfirm('entered-error')}
      />
      <div data-note-save-bar={breakpoint} style={stickySave}>
        <Button onClick={() => void workflow.handleSave()} disabled={!dirty}>
          {chartText(locale, 'saveDraft')}
        </Button>
      </div>

      <NoteConfirmations workflow={workflow} draft={draft} source={source} locale={locale} />
    </div>
  );
}
