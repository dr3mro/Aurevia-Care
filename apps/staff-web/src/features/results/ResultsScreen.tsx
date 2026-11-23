import { useState } from 'react';

import { Button } from '../../components/Button';
import { useI18n } from '../../i18n/I18nProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { CriticalFindings } from './CriticalFindings';
import { filterInbox, inboxCounts } from './domain';
import {
  filterMessageKeys,
  resultsText,
  type ResultsMessageKey,
} from './resultsMessages';
import { ReconciliationPanel } from './ReconciliationPanel';
import { ResultsInbox } from './ResultsInbox';
import type { ResultsFilter, ResultsSnapshot } from './types';

/** The chips are rendered in a fixed, bilingual order. */
const FILTERS: readonly ResultsFilter[] = ['mine', 'coverage', 'unmatched'];

/** Props for the presentational results screen. */
export interface ResultsScreenProps {
  snapshot: ResultsSnapshot;
  now: string;
  timeZone: string;
  viewerId: string;
  onReadBack: (id: string) => void;
  onAcknowledge: (id: string) => void;
  onEscalate: (id: string) => void;
  onAskLab: () => void;
  onAssignOwner: () => void;
  onLink: () => void;
}

/**
 * Compose the results page content without any network access.
 *
 * Only the transient chip filter lives here; all durable commands belong to
 * the page. The root is a transparent stack so `ApiPage` remains the single
 * owner of the `.page` layout. The wide inbox and narrow reconciliation panel
 * use the shared `grid-2`, which collapses to one column at compact widths.
 */
export function ResultsScreen({
  snapshot,
  now,
  timeZone,
  viewerId,
  onReadBack,
  onAcknowledge,
  onEscalate,
  onAskLab,
  onAssignOwner,
  onLink,
}: ResultsScreenProps) {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const [filter, setFilter] = useState<ResultsFilter>('mine');
  const counts = inboxCounts(snapshot.rows, viewerId);
  const visibleRows = filterInbox(snapshot.rows, filter, viewerId);
  const stacked = breakpoint === 'compact';

  return (
    <div
      className="stack"
      data-breakpoint={breakpoint}
      aria-label={resultsText(locale, 'title')}
      style={{ overflowWrap: 'anywhere' }}
    >
      <header className="stack">
        <h2>{resultsText(locale, 'title')}</h2>
        <p>{resultsText(locale, 'subtitle')}</p>
        <p>{resultsText(locale, 'syntheticNotice')}</p>
        <div className="cluster" role="group" aria-label={resultsText(locale, 'filterGroup')}>
          {FILTERS.map((candidate) => {
            const key: ResultsMessageKey = filterMessageKeys[candidate];
            const label = resultsText(locale, key);
            return (
              <Button
                key={candidate}
                variant="secondary"
                pressed={filter === candidate}
                onClick={() => setFilter(candidate)}
              >
                {label} {counts[candidate]}
              </Button>
            );
          })}
        </div>
      </header>
      <CriticalFindings
        findings={snapshot.findings}
        locale={locale}
        now={now}
        timeZone={timeZone}
        onReadBack={onReadBack}
        onAcknowledge={onAcknowledge}
        onEscalate={onEscalate}
      />
      <div className="grid-2" data-layout={stacked ? 'stacked' : 'side-by-side'}>
        <ResultsInbox rows={visibleRows} locale={locale} />
        <ReconciliationPanel
          reconciliation={snapshot.reconciliation}
          locale={locale}
          onAskLab={onAskLab}
          onAssignOwner={onAssignOwner}
          onLink={onLink}
        />
      </div>
    </div>
  );
}
