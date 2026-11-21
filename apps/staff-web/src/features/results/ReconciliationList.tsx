import { useState } from 'react';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { formatInstant } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { listReconciliations } from './api';
import type { ReconciliationEntry, ReconciliationStatus } from './domain';
import { reconciliationReasonKey, resultText } from './messages';

/** Props for the tenant-scoped reconciliation queue. */
export interface ReconciliationListProps {
  organizationId: string;
  locale: Locale;
}

/** Render the two queue statuses as explicit toggles on the status filter. */
function StatusFilter({
  status,
  locale,
  onStatus,
}: {
  status: ReconciliationStatus;
  locale: Locale;
  onStatus: (status: ReconciliationStatus) => void;
}) {
  return (
    <div role="group" aria-label={resultText(locale, 'status')}>
      <Button pressed={status === 'open'} onClick={() => onStatus('open')}>
        {resultText(locale, 'statusOpen')}
      </Button>{' '}
      <Button pressed={status === 'resolved'} onClick={() => onStatus('resolved')}>
        {resultText(locale, 'statusResolved')}
      </Button>
    </div>
  );
}

/** Render one page of reconciliation entries as an accessible table. */
function ReconciliationTable({
  entries,
  locale,
}: {
  entries: readonly ReconciliationEntry[];
  locale: Locale;
}) {
  const { tokens } = useTheme();
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
        {resultText(locale, 'reconciliationQueue')}
      </caption>
      <thead>
        <tr>
          <th scope="col">{resultText(locale, 'patient')}</th>
          <th scope="col">{resultText(locale, 'reason')}</th>
          <th scope="col">{resultText(locale, 'mismatches')}</th>
          <th scope="col">{resultText(locale, 'detail')}</th>
          <th scope="col">{resultText(locale, 'createdAt')}</th>
        </tr>
      </thead>
      <tbody>
        {entries.map((entry) => (
          <tr key={entry.id}>
            <th scope="row">
              <MixedScriptText text={entry.identifiers.patientId} lang="en" />
            </th>
            <td>
              <MixedScriptText text={resultText(locale, reconciliationReasonKey[entry.reason])} lang={locale} />
            </td>
            <td>
              <MixedScriptText text={entry.mismatches.join(', ')} lang="en" />
            </td>
            <td>
              <MixedScriptText text={entry.detail} lang={locale} />
            </td>
            <td>
              <MixedScriptText
                text={formatInstant(entry.createdAt, 'UTC', locale)}
                lang={locale}
              />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * Inbox of unmatched and conflicting reports. The API returns a bounded list by
 * status without a cursor, so no "Load more" control is offered here.
 */
export function ReconciliationList({ organizationId, locale }: ReconciliationListProps) {
  const { tokens } = useTheme();
  const [status, setStatus] = useState<ReconciliationStatus>('open');
  return (
    <section aria-label={resultText(locale, 'reconciliationQueue')} style={{ display: 'grid', gap: tokens.spacing.md }}>
      <StatusFilter status={status} locale={locale} onStatus={setStatus} />
      <ApiPage
        resource={{
          label: resultText(locale, 'reconciliationQueue'),
          load: () => listReconciliations(status, organizationId),
          deps: [status, organizationId],
        }}
        isEmpty={(entries) => entries.length === 0}
      >
        {(entries) => <ReconciliationTable entries={entries} locale={locale} />}
      </ApiPage>
    </section>
  );
}
