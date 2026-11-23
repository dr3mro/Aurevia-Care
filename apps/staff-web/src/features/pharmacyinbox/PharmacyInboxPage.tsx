import { useMemo, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { listPrescriptions } from './api';
import { useApi } from '../../api/useApi';
import { navigate } from '../../app/router';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { filterMessageKey, pharmacyText, stateMessageKey } from './labels';
import { STATUS_FILTERS } from './types';
import type { InboxItemResponse, InboxPageResponse, StatusFilter } from './types';

/**
 * Tenant-scoped pharmacy inbox (RX-02, WEB-09). One page is fetched at a time;
 * Load more appends the next cursor page and never fetches the whole worklist.
 */
export function PharmacyInboxPage() {
  const organizationId = useActiveOrganization();
  // A tenant switch remounts the inbox, which invalidates every accumulated
  // page before the new request can respond.
  return <PharmacyInboxContent key={organizationId} organizationId={organizationId} />;
}

function PharmacyInboxContent({ organizationId }: { organizationId: string }) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [filter, setFilter] = useState<StatusFilter>('all');
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<readonly InboxItemResponse[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [appliedPage, setAppliedPage] = useState<InboxPageResponse | undefined>(undefined);

  const result = useApi<InboxPageResponse>(
    () => listPrescriptions(filter, cursor, organizationId),
    [filter, cursor, organizationId],
  );

  // Append each resolved page. The dependency is the page itself, so an
  // unrelated render can never duplicate the same rows.
  if (result.status === 'ready' && result.data !== appliedPage) {
    const page = result.data;
    setAppliedPage(page);
    setAccumulated((current) => [...current, ...page.items]);
    setNextCursor(page.nextCursor ?? undefined);
  }

  function handleFilter(next: StatusFilter) {
    setFilter(next);
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
  }

  function handleLoadMore() {
    setCursor(nextCursor);
  }

  const rows = useMemo(
    () =>
      accumulated.map((item) => ({
        item,
        stateLabel: pharmacyText(locale, stateMessageKey(item.state)),
        openLabel: `${pharmacyText(locale, 'openItem')} ${item.patientId} ${item.medication}`,
      })),
    [accumulated, locale],
  );

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{pharmacyText(locale, 'pharmacy')}</h2>
        <p>{pharmacyText(locale, 'syntheticNotice')}</p>
      </header>
      <div role="group" aria-label={pharmacyText(locale, 'filterLabel')}>
        {STATUS_FILTERS.map((option) => (
          <Button
            key={option}
            variant={filter === option ? 'primary' : 'secondary'}
            pressed={filter === option}
            onClick={() => handleFilter(option)}
          >
            {`${pharmacyText(locale, 'filterLabel')}: ${pharmacyText(locale, filterMessageKey(option))}`}
          </Button>
        ))}
      </div>
      {result.status === 'loading' && rows.length === 0 ? (
        <LoadingState />
      ) : result.status === 'error' && rows.length === 0 ? (
        StateView.forTransportError(result.error)
      ) : rows.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <table style={{ borderCollapse: 'collapse', width: '100%' }}>
            <caption style={{ textAlign: 'start' }}>
              {pharmacyText(locale, 'worklistCaption')}
            </caption>
            <thead>
              <tr>
                <th scope="col">{pharmacyText(locale, 'patient')}</th>
                <th scope="col">{pharmacyText(locale, 'medication')}</th>
                <th scope="col">{pharmacyText(locale, 'dose')}</th>
                <th scope="col">{pharmacyText(locale, 'quantity')}</th>
                <th scope="col">{pharmacyText(locale, 'state')}</th>
                <th scope="col">{pharmacyText(locale, 'actions')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ item, stateLabel, openLabel }) => (
                <tr key={item.id}>
                  <th scope="row">
                    <MixedScriptText text={item.patientId} lang="en" />
                  </th>
                  <td>{item.medication}</td>
                  <td>{item.dose}</td>
                  <td>{item.quantity}</td>
                  <td>{stateLabel}</td>
                  <td>
                    <Button onClick={() => navigate(`/pharmacy/${encodeURIComponent(item.id)}`)}>
                      {openLabel}
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {nextCursor !== undefined ? (
            <Button onClick={handleLoadMore} disabled={result.status === 'loading'}>
              {pharmacyText(locale, 'loadMore')}
            </Button>
          ) : null}
        </>
      )}
    </div>
  );
}
