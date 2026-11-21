import { useEffect, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import type { TransportErrorKind } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { useRoute } from '../../app/router';
import { Button } from '../../components/Button';
import { ConflictState, LoadingState, StateView } from '../../components/StateViews';
import { Dialog } from '../../components/Dialog';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
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
 * API-backed note editor for `/patients/:id/notes/:noteId`.
 *
 * Draft text lives only in component state — never in browser storage. A stale
 * save leaves the typed text intact and offers compare, reload and copy. Signing
 * and entered-in-error are irreversible, so each asks for explicit confirmation
 * that names the note and its effect.
 */
export function NotePage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
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

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      {result.status === 'error' ? (
        <div data-testid="note-page-load-error">{StateView.forTransportError(result.error)}</div>
      ) : null}
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{chartText(locale, 'clinicalNote')}</h2>
        <p style={{ marginBlock: 0 }}>
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
        <section data-testid="note-compare" style={{ display: 'grid', gap: tokens.spacing.sm }}>
          <h3>{chartText(locale, 'compareTitle')}</h3>
          <b>{chartText(locale, 'serverContent')}</b>
          <pre>{source!.content}</pre>
          <b>{chartText(locale, 'yourContent')}</b>
          <pre>{soapToContent(draft.content)}</pre>
        </section>
      ) : null}

      <NoteEditor
        key={draft.id}
        revision={draft}
        history={history}
        locale={locale}
        onContentChange={handleContentChange}
        onSign={() => setConfirm('sign')}
        onAmend={() => void handleAmend()}
        onMarkEnteredInError={() => setConfirm('entered-error')}
      />
      <Button onClick={() => void handleSave()} disabled={!dirty}>
        {chartText(locale, 'saveDraft')}
      </Button>

      <Dialog
        open={confirm === 'sign'}
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
      </Dialog>

      <Dialog
        open={confirm === 'entered-error'}
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
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
        <Button disabled={reason.trim().length === 0} onClick={() => void handleEnteredInError()}>
          {chartText(locale, 'markEnteredInError')}
        </Button>
      </Dialog>
    </div>
  );
}
