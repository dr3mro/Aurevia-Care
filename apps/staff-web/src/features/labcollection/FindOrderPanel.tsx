import { useState } from 'react';
import type { SyntheticEvent } from 'react';
import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { annotatedName } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { CollectionOrder, FindMode } from './domain';
import { findOrders } from './domain';
import { labText } from './messages';

/** Props for the order search panel of the collection desk. */
export interface FindOrderPanelProps {
  orders: readonly CollectionOrder[];
  selectedId: string | undefined;
  onSelect: (id: string) => void;
}

/**
 * Find an order by accession number or by patient. The results are derived
 * from the orders the page already loaded for the active tenant; the panel
 * never fetches or stores patient data itself.
 */
export function FindOrderPanel({ orders, selectedId, onSelect }: FindOrderPanelProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [mode, setMode] = useState<FindMode>('accession');
  const [query, setQuery] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState('');
  const results = findOrders(orders, mode, query);

  function submit(event: SyntheticEvent) {
    // Filtering is live; the explicit Find action gives keyboard users a
    // predictable submit affordance and keeps the query in local state.
    event.preventDefault();
    setSubmittedQuery(query);
  }

  return (
    <section
      aria-label={labText(locale, 'findOrder')}
      style={{ display: 'grid', gap: tokens.spacing.md }}
    >
      <h3>{labText(locale, 'findOrder')}</h3>
      <div>
        <Button
          variant={mode === 'accession' ? 'primary' : 'secondary'}
          pressed={mode === 'accession'}
          onClick={() => setMode('accession')}
        >
          {labText(locale, 'searchByAccession')}
        </Button>{' '}
        <Button
          variant={mode === 'patient' ? 'primary' : 'secondary'}
          pressed={mode === 'patient'}
          onClick={() => setMode('patient')}
        >
          {labText(locale, 'searchByPatient')}
        </Button>
      </div>
      <form aria-label={labText(locale, 'findOrder')} onSubmit={submit}>
        <FormField
          id="lab-find-query"
          label={labText(locale, 'search')}
          value={query}
          placeholder={labText(locale, 'searchHint')}
          onChange={setQuery}
        />
        <Button type="submit">{labText(locale, 'search')}</Button>
      </form>
      <p role="status" data-testid="find-status">
        {submittedQuery === '' ? '' : `${labText(locale, 'search')}: ${submittedQuery}`}
      </p>
      {results.length === 0 ? (
        <p role="status">{labText(locale, 'noOrdersFound')}</p>
      ) : (
        <ul aria-label={labText(locale, 'findOrder')}>
          {results.map((order) => (
            <li key={`${order.organizationId}:${order.id}`}>
              <Button pressed={selectedId === order.id} onClick={() => onSelect(order.id)}>
                <code dir="ltr">{order.accessionNo}</code> —{' '}
                <MixedScriptText
                  text={annotatedName(order.patientName, locale).text}
                  lang={annotatedName(order.patientName, locale).lang}
                />
              </Button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
