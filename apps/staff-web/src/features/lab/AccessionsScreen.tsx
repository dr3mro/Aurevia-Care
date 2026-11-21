import { useState } from 'react';
import { Link } from '../../app/router';
import { Button } from '../../components/Button';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { AccessionForm } from './AccessionForm';
import { AccessionStateBadge } from './AccessionStateBadge';
import { ACCESSION_STATES } from './domain';
import { labText, stateMessageKey } from './messages';
import type { AccessionBody, AccessionState, AccessionView } from './types';

/** Props for the accession list screen. */
export interface AccessionsScreenProps {
  items: readonly AccessionView[];
  filter: AccessionState | '';
  onFilterChange: (state: AccessionState | '') => void;
  onCreate: (body: AccessionBody) => void;
  /** Issuer-scoped id of a just-created accession, shown after the POST. */
  createdId: string | undefined;
}

/**
 * Render the state-filtered accession worklist, the create command and the
 * issuer-scoped id returned after creation. Rows link by opaque identifier;
 * no patient name or free text reaches the URL.
 */
export function AccessionsScreen({
  items,
  filter,
  onFilterChange,
  onCreate,
  createdId,
}: AccessionsScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [showForm, setShowForm] = useState(false);

  return (
    <section aria-label={labText(locale, 'accessions')} style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <h2>{labText(locale, 'accessions')}</h2>
      <p>{labText(locale, 'syntheticNotice')}</p>

      <label htmlFor="accession-state-filter">{labText(locale, 'stateFilter')}</label>
      <select
        id="accession-state-filter"
        value={filter}
        onChange={(event) => onFilterChange(event.target.value as AccessionState | '')}
      >
        <option value="">{labText(locale, 'allStates')}</option>
        {ACCESSION_STATES.map((state) => (
          <option key={state} value={state}>
            {labText(locale, stateMessageKey[state])}
          </option>
        ))}
      </select>

      {createdId !== undefined && (
        <p role="status">
          {labText(locale, 'accessionCreated')}: <code dir="ltr">{createdId}</code>
        </p>
      )}

      {showForm ? (
        <AccessionForm
          onSubmit={(body) => {
            onCreate(body);
            setShowForm(false);
          }}
          onCancel={() => setShowForm(false)}
        />
      ) : (
        <Button onClick={() => setShowForm(true)}>{labText(locale, 'newAccession')}</Button>
      )}

      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start' }}>{labText(locale, 'accessions')}</caption>
        <thead>
          <tr>
            <th scope="col">{labText(locale, 'accessionNo')}</th>
            <th scope="col">{labText(locale, 'accessionIssuer')}</th>
            <th scope="col">{labText(locale, 'state')}</th>
            <th scope="col">{labText(locale, 'specimenType')}</th>
            <th scope="col">{labText(locale, 'siteCode')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((view) => (
            <tr key={view.accession.id}>
              <td>
                <Link to={`/lab/accessions/${view.accession.id}`}>{view.accession.accessionNo}</Link>
              </td>
              <td>{view.accession.accessionIssuer}</td>
              <td>
                <AccessionStateBadge state={view.accession.state} />
              </td>
              <td>{view.accession.specimenType ?? ''}</td>
              <td>{view.accession.siteCode ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
