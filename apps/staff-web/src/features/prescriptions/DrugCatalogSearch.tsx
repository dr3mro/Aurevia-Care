import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { DrugCandidate } from './domain';
import { prescriptionText } from './messages';

/** Props for the market-approved drug catalog search and alternatives list. */
export interface DrugCatalogSearchProps {
  results: readonly DrugCandidate[];
  query: string;
  formulation: string;
  selectedCode: string;
  onQueryChange: (value: string) => void;
  onFormulationChange: (value: string) => void;
  onSearch: () => void;
  onSelect: (drug: DrugCandidate) => void;
}

/**
 * Search the approved catalog and list tagged alternatives. The search never
 * calls the network itself; the page owns the API request and passes results.
 */
export function DrugCatalogSearch({
  results,
  query,
  formulation,
  selectedCode,
  onQueryChange,
  onFormulationChange,
  onSearch,
  onSelect,
}: DrugCatalogSearchProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <section
      aria-label={prescriptionText(locale, 'drugSearch')}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h3>{prescriptionText(locale, 'drugSearch')}</h3>
      <FormField
        id="drug-query"
        label={prescriptionText(locale, 'searchQuery')}
        value={query}
        onChange={onQueryChange}
        placeholder={prescriptionText(locale, 'searchQueryPlaceholder')}
      />
      <FormField
        id="drug-formulation"
        label={prescriptionText(locale, 'formulation')}
        value={formulation}
        onChange={onFormulationChange}
        placeholder={prescriptionText(locale, 'formulationPlaceholder')}
      />
      <div>
        <Button onClick={onSearch}>{prescriptionText(locale, 'search')}</Button>
      </div>
      {results.length === 0 ? (
        <p role="status" data-testid="drug-no-matches">
          {prescriptionText(locale, 'noMatches')}
        </p>
      ) : (
        <ul style={{ display: 'grid', gap: tokens.spacing.sm }}>
          {results.map((drug) => (
            <li key={drug.code} data-testid="drug-option" data-selected={drug.code === selectedCode}>
              {/* Catalog names are Latin-script identifiers (RTL §4). */}
              <MixedScriptText text={`${drug.genericName} (${drug.tradeName})`} lang="en" />
              <span> {drug.strength}</span>
              <span aria-label={prescriptionText(locale, 'taggedAlternative')}>
                {` [${drug.matchTags.join(', ')}]`}
              </span>
              <Button onClick={() => onSelect(drug)}>{prescriptionText(locale, 'select')}</Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
