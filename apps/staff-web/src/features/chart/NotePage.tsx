import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import type { TransportErrorKind } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { useRoute } from '../../app/router';
import { Button } from '../../components/Button';
import { ConflictState, LoadingState, StateView } from '../../components/StateViews';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
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
type NotePageData = { patient: PatientViewResponse; noteView: NoteViewResponse };

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
    closeRef.current!.focus();
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

  const result = useApi<NotePageData>(
    () =>
      Promise.all([
        getPatient(patientId, organizationId),
        getNote(noteId, organizationId),
      ]).then(([patient, noteView]) => ({ patient, noteView })),
    [patientId, noteId, organizationId],
  );

  // Initialise (or re-initialise after a command reload) from the authoritative
  // revision. Typing does not re-run this effect because the result is stable.
  useEffect(() => {
    if (result.status !== 'ready') {
      return;
    }
    setDraft(toNoteRevision(result.data.noteView.current, organizationId));
    setHistory(result.data.noteView.history.map((item) => toNoteRevision(item, organizationId)));
    setSource(result.data.noteView.current);
    setDirty(false);
    setConflict(false);
    setComparing(false);
  }, [result.status, result.data, organizationId]);

  // A browser-level guard asks before leaving with unsaved draft text.
  useEffect(() => {
    if (!dirty) {
      return undefined;
    }
    const warn = (event: BeforeUnloadEvent): void => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  function recordFailure(failure: unknown): void {
    const error = toTransportError(failure);
    setCommandError(error);
    setConflict(isConflict(error.kind));
    setStatus('');
  }

  function handleContentChange(field: keyof SoapContent, value: string): void {
    const current = draft!;
    setDraft({ ...current, content: { ...current.content, [field]: value } });
    setDirty(true);
    setStatus('');
  }

  async function handleSave(): Promise<void> {
    setStatus(chartText(locale, 'saving'));
    try {
      await updateNote(
        noteId!,
        draft!.version,
        { title: source!.title, content: soapToContent(draft!.content) },
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
    const attestation = chartText(locale, 'attestation');
    try {
      const signed =
        draft!.kind === 'amendment'
          ? await signAddendum(noteId!, draft!.version, attestation, organizationId)
          : await signNote(noteId!, draft!.version, attestation, organizationId);
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
    try {
      await amendNote(noteId!, draft!.version, organizationId);
      setCommandError(undefined);
      setStatus(chartText(locale, 'amendNotice'));
      result.reload();
    } catch (failure: unknown) {
      recordFailure(failure);
    }
  }

  async function handleEnteredInError(): Promise<void> {
    try {
      await markEnteredInError(
        noteId!,
        draft!.version,
        reason,
        [source!.dataEntrySubject],
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

  function handleReload(): void {
    setConflict(false);
    setCommandError(undefined);
    setComparing(false);
    setCopied(false);
    setStatus('');
    result.reload();
  }

  function handleCopy(): void {
    void navigator.clipboard?.writeText(soapToContent(draft!.content));
    setCopied(true);
  }

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
  const stickySave = breakpoint === 'compact'
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
          <b>{chartText(locale, 'patientId')}:</b>{' '}
          <code dir="ltr">{patientId}</code>
        </p>
      </header>

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
            onCompare={() => setComparing(true)}
            onReload={handleReload}
            onCopy={handleCopy}
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
          <pre style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>{source!.content}</pre>
          <b>{chartText(locale, 'yourContent')}</b>
          <pre style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>
            {soapToContent(draft.content)}
          </pre>
        </section>
      ) : null}

      <NoteEditor
        key={draft.id}
        revision={draft}
        history={history}
        locale={locale}
        onContentChange={handleContentChange}
        onSign={() => {
          // The sign command carries no edited content: signing while dirty
          // would silently certify the last saved revision, not what is shown.
          if (dirty) {
            setStatus(chartText(locale, 'saveBeforeSign'));
            return;
          }
          setConfirm('sign');
        }}
        onAmend={() => void handleAmend()}
        onMarkEnteredInError={() => setConfirm('entered-error')}
      />
      <div data-note-save-bar={breakpoint} style={stickySave}>
        <Button onClick={() => void handleSave()} disabled={!dirty}>
          {chartText(locale, 'saveDraft')}
        </Button>
      </div>

      {confirm === 'sign' ? (
        <ConfirmationDialog
          title={chartText(locale, 'signConfirmTitle')}
          closeLabel={chartText(locale, 'cancel')}
          onClose={() => setConfirm(undefined)}
        >
          <p>
            <MixedScriptText text={source!.title} lang={locale} /> ·{' '}
            {chartText(locale, 'revision')} {draft.version}
          </p>
          <p>{chartText(locale, 'signEffect')}</p>
          <p>
            <b>{chartText(locale, 'signerAttestation')}:</b> {chartText(locale, 'attestation')}
          </p>
          <Button onClick={() => void handleSign()}>{chartText(locale, 'sign')}</Button>
        </ConfirmationDialog>
      ) : null}

      {confirm === 'entered-error' ? (
        <ConfirmationDialog
          title={chartText(locale, 'markEnteredInError')}
          closeLabel={chartText(locale, 'cancel')}
          onClose={() => setConfirm(undefined)}
        >
          <p>
            <MixedScriptText text={source!.title} lang={locale} /> ·{' '}
            {chartText(locale, 'revision')} {draft.version}
          </p>
          <p>{chartText(locale, 'enterErrorEffect')}</p>
          <label htmlFor="chart-enter-error-reason">{chartText(locale, 'enterErrorReason')}</label>
          <input
            id="chart-enter-error-reason"
            className="field touch"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
          <Button disabled={reason.trim().length === 0} onClick={() => void handleEnteredInError()}>
            {chartText(locale, 'markEnteredInError')}
          </Button>
        </ConfirmationDialog>
      ) : null}
    </div>
  );
}
