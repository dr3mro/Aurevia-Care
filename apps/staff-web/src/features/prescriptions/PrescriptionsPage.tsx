import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { Dialog } from '../../components/Dialog';
import {
  ConflictState,
  EmptyState,
  ErrorState,
  ForbiddenState,
  LoadingState,
  SessionExpiredState,
} from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { getPrescription, reconcileTransmission, searchDrugCatalog, submitPrescription } from './api';
import type { PrescriptionResponse } from './api';
import type { DrugCandidate, DraftFields } from './domain';
import { findAllergyFindings, selectDrugs } from './domain';
import {
  createSyntheticDraft,
  syntheticAllergies,
  syntheticSelectedDrug,
} from './fixtures';
import { prescriptionText } from './messages';
import { PrescriptionsScreen } from './PrescriptionsScreen';

/** Normalise an unexpected command rejection to a typed transport failure. */
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
 * Present one blocking state with a working retry where retry is meaningful.
 * The shared `StateView` has no callback argument, so the page maps the typed
 * kinds itself while still showing only machine-derived text.
 */
function stateForError(error: TransportError, onRetry: () => void): ReactNode {
  if (error.kind === 'forbidden') {
    return <ForbiddenState />;
  }
  if (error.kind === 'session-expired') {
    return <SessionExpiredState />;
  }
  return <ErrorState correlationId={error.correlationId} onRetry={onRetry} />;
}

/**
 * Load the approved catalog, keep the prescriber's draft across every state
 * change, and dispatch attest/route and partner-reconciliation commands. A
 * conflict keeps the typed work and offers compare, reload and copy; nothing
 * is ever written to browser storage.
 */
export function PrescriptionsPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [query, setQuery] = useState('');
  const [formulation, setFormulation] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [submittedFormulation, setSubmittedFormulation] = useState('');
  const [draft, setDraft] = useState<DraftFields>(() => createSyntheticDraft());
  const [selectedDrug, setSelectedDrug] = useState<DrugCandidate>(syntheticSelectedDrug);
  const [response, setResponse] = useState<PrescriptionResponse | undefined>(undefined);
  const [existing, setExisting] = useState<{ id: string; version: number } | undefined>(undefined);
  const [openedDraft, setOpenedDraft] = useState<DraftFields>(() => createSyntheticDraft());
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [lastCommand, setLastCommand] = useState<'submit' | 'reconcile' | undefined>(undefined);
  const [announcement, setAnnouncement] = useState('');
  const [compareOpen, setCompareOpen] = useState(false);
  const [copied, setCopied] = useState(false);

  const result = useApi<readonly DrugCandidate[]>(
    () => searchDrugCatalog(submittedQuery, submittedFormulation, organizationId),
    [submittedQuery, submittedFormulation],
  );
  const { reload: refetch } = result;
  const results =
    result.status === 'ready'
      ? selectDrugs(result.data, submittedQuery, submittedFormulation)
      : [];
  const findings = findAllergyFindings(selectedDrug, syntheticAllergies);
  const dirty = JSON.stringify(draft) !== JSON.stringify(openedDraft);

  // A browser-level guard asks before leaving with unsaved clinical text. The
  // message is the browser's own; no patient data is placed in it.
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

  // Switching organization drops every tenant-scoped projection and draft.
  useEffect(() => {
    setQuery('');
    setFormulation('');
    setSubmittedQuery('');
    setSubmittedFormulation('');
    setDraft(createSyntheticDraft());
    setSelectedDrug(syntheticSelectedDrug);
    setResponse(undefined);
    setExisting(undefined);
    setOpenedDraft(createSyntheticDraft());
    setCommandError(undefined);
    setLastCommand(undefined);
    setAnnouncement('');
    setCompareOpen(false);
    setCopied(false);
  }, [organizationId]);

  function handleSearch() {
    setSubmittedQuery(query);
    setSubmittedFormulation(formulation);
  }

  function handleSelect(drug: DrugCandidate) {
    setSelectedDrug(drug);
    setDraft((current) => ({
      ...current,
      drugCode: drug.code,
      drugVersion: drug.version,
      strength: drug.strength,
      formulation: drug.formulation,
    }));
    setAnnouncement(drug.genericName);
  }

  function handleDraftChange(patch: Partial<DraftFields>) {
    setDraft((current) => ({ ...current, ...patch }));
  }

  async function handleSubmit() {
    setLastCommand('submit');
    try {
      const saved = await submitPrescription(draft, organizationId, existing);
      setResponse(saved);
      setExisting({ id: saved.id, version: saved.version });
      setOpenedDraft(draft);
      setCommandError(undefined);
      setAnnouncement(prescriptionText(locale, 'submittedNotice'));
    } catch (failure: unknown) {
      setCommandError(toTransportError(failure));
    }
  }

  async function handleReconcile() {
    // The reconcile control only renders once a response exists.
    const target = response!;
    setLastCommand('reconcile');
    try {
      const saved = await reconcileTransmission(target.id, organizationId);
      setResponse(saved);
      setExisting({ id: saved.id, version: saved.version });
      setOpenedDraft(draft);
      setCommandError(undefined);
      setAnnouncement(prescriptionText(locale, 'reconciledNotice'));
    } catch (failure: unknown) {
      setCommandError(toTransportError(failure));
    }
  }

  function handleCompare() {
    setCompareOpen(true);
  }

  async function handleReload() {
    setCompareOpen(false);
    setCommandError(undefined);
    setCopied(false);
    // Before the first successful submit there is no record to reload; the
    // catalog is the only authoritative data the screen owns at that point.
    if (existing === undefined) {
      refetch();
      return;
    }
    try {
      const current = await getPrescription(existing.id, organizationId);
      setResponse(current);
      setExisting({ id: current.id, version: current.version });
      setAnnouncement(prescriptionText(locale, 'reloadedNotice'));
    } catch (failure: unknown) {
      setCommandError(toTransportError(failure));
    }
  }

  function handleRetryCommand() {
    if (lastCommand === 'reconcile') {
      void handleReconcile();
    } else {
      void handleSubmit();
    }
  }

  function handleCopy() {
    setCopied(true);
    setAnnouncement(prescriptionText(locale, 'copied'));
  }

  const isConflict =
    commandError !== undefined &&
    (commandError.kind === 'conflict' || commandError.kind === 'precondition-required');

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      {commandError !== undefined ? (
        isConflict ? (
          <div data-testid="prescriptions-conflict">
            <ConflictState onCompare={handleCompare} onReload={handleReload} onCopy={handleCopy} />
          </div>
        ) : (
          <div data-testid="prescriptions-command-error">
            {stateForError(commandError, handleRetryCommand)}
          </div>
        )
      ) : null}
      {copied ? (
        <section role="status" data-testid="copied-draft">
          <p>{prescriptionText(locale, 'copied')}</p>
          <textarea
            readOnly
            aria-label={prescriptionText(locale, 'copied')}
            value={JSON.stringify(draft)}
          />
        </section>
      ) : null}
      <Dialog
        open={compareOpen}
        title={prescriptionText(locale, 'compareTitle')}
        closeLabel={prescriptionText(locale, 'close')}
        onClose={() => setCompareOpen(false)}
      >
        <p>
          {prescriptionText(locale, 'openedVersion')}: {existing?.version ?? 1}
        </p>
        <dl>
          <dt>{prescriptionText(locale, 'medication')}</dt>
          <dd>{`${openedDraft.drugCode} ${prescriptionText(locale, 'changedTo')} ${draft.drugCode}`}</dd>
          <dt>{prescriptionText(locale, 'strength')}</dt>
          <dd>{`${openedDraft.strength} ${prescriptionText(locale, 'changedTo')} ${draft.strength}`}</dd>
          <dt>{prescriptionText(locale, 'instructions')}</dt>
          <dd>{`${openedDraft.instructions} ${prescriptionText(locale, 'changedTo')} ${draft.instructions}`}</dd>
        </dl>
      </Dialog>
      {result.status === 'loading' && results.length === 0 ? (
        <LoadingState />
      ) : result.status === 'error' && results.length === 0 ? (
        stateForError(result.error, refetch)
      ) : results.length === 0 ? (
        <EmptyState />
      ) : (
        <PrescriptionsScreen
          catalog={results}
          findings={findings}
          draft={draft}
          events={response?.events ?? []}
          submissionId={response?.submissionId ?? null}
          transmission={response?.transmission ?? null}
          announcement={announcement}
          query={query}
          formulation={formulation}
          onQueryChange={setQuery}
          onFormulationChange={setFormulation}
          onSearch={handleSearch}
          onSelect={handleSelect}
          onDraftChange={handleDraftChange}
          onSubmit={handleSubmit}
          onReconcile={handleReconcile}
        />
      )}
    </div>
  );
}
