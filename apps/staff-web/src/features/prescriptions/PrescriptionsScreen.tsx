import { Button } from '../../components/Button';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { AllergyWarning } from './AllergyWarning';
import { DrugCatalogSearch } from './DrugCatalogSearch';
import { DraftForm } from './DraftForm';
import { StatusTimeline } from './StatusTimeline';
import type {
  AllergyFinding,
  DrugCandidate,
  DraftFields,
  PrescriptionEvent,
  TransmissionOutcome,
} from './domain';
import { canSubmit } from './domain';
import { prescriptionText } from './messages';

/** Props for the presentational prescriptions screen. */
export interface PrescriptionsScreenProps {
  catalog: readonly DrugCandidate[];
  findings: readonly AllergyFinding[];
  draft: DraftFields;
  events: readonly PrescriptionEvent[];
  submissionId: string | null;
  transmission: TransmissionOutcome | null;
  announcement: string;
  query: string;
  formulation: string;
  onQueryChange: (value: string) => void;
  onFormulationChange: (value: string) => void;
  onSearch: () => void;
  onSelect: (drug: DrugCandidate) => void;
  onDraftChange: (patch: Partial<DraftFields>) => void;
  onSubmit: () => void;
  onReconcile: () => void;
}

/**
 * Compose the catalog search, allergy review, draft form and status timeline.
 * All data and durable commands belong to the page; this screen only renders
 * what it is given and reports the prescriber's actions through callbacks.
 */
export function PrescriptionsScreen({
  catalog,
  findings,
  draft,
  events,
  submissionId,
  transmission,
  announcement,
  query,
  formulation,
  onQueryChange,
  onFormulationChange,
  onSearch,
  onSelect,
  onDraftChange,
  onSubmit,
  onReconcile,
}: PrescriptionsScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <section
      aria-label={prescriptionText(locale, 'prescriptions')}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h2>{prescriptionText(locale, 'prescriptions')}</h2>
      <p>{prescriptionText(locale, 'syntheticNotice')}</p>
      <p
        role="status"
        data-testid="prescriptions-status"
        aria-label={prescriptionText(locale, 'statusTimeline')}
      >
        {announcement}
      </p>
      <AllergyWarning findings={findings} />
      <DrugCatalogSearch
        results={catalog}
        query={query}
        formulation={formulation}
        selectedCode={draft.drugCode}
        onQueryChange={onQueryChange}
        onFormulationChange={onFormulationChange}
        onSearch={onSearch}
        onSelect={onSelect}
      />
      <DraftForm
        draft={draft}
        findings={findings}
        submitDisabled={!canSubmit(draft, findings)}
        onChange={onDraftChange}
        onSubmit={onSubmit}
      />
      <StatusTimeline events={events} submissionId={submissionId} />
      {transmission === 'ambiguous' ? (
        <div>
          <p role="alert" data-testid="ambiguous-notice">
            {prescriptionText(locale, 'ambiguousNotice')}
          </p>
          <Button onClick={onReconcile}>{prescriptionText(locale, 'reconcile')}</Button>
        </div>
      ) : null}
    </section>
  );
}
