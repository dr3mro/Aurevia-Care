import { useCallback, useEffect, useMemo, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { Button } from '../../components/Button';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { accession, listAccessions } from './api';
import { AccessionsScreen } from './AccessionsScreen';
import type { AccessionBody, AccessionPage, AccessionState, AccessionView } from './types';
import { labText } from './messages';

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

/** API-backed accession worklist with state filter, cursor paging and create. */
export function AccessionsPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [filter, setFilter] = useState<AccessionState | ''>('');
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<readonly AccessionView[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [createdId, setCreatedId] = useState<string | undefined>(undefined);

  const result = useApi<AccessionPage>(
    () => listAccessions({ cursor, state: filter || undefined }, organizationId),
    [cursor, filter, organizationId],
  );

  useEffect(() => {
    if (result.status !== 'ready') {
      return;
    }
    const page = result.data;
    setAccumulated((current) => [...current, ...page.items]);
    setNextCursor(page.nextCursor ?? undefined);
  }, [result.status, result.data]);

  // A tenant switch or a new state filter invalidates every accumulated page.
  useEffect(() => {
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
    setCommandError(undefined);
    setCreatedId(undefined);
  }, [organizationId, filter]);

  const items = useMemo(() => [...accumulated], [accumulated]);

  const { reload: refetch } = result;
  const reload = useCallback(() => {
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
    setCommandError(undefined);
    refetch();
  }, [refetch]);

  async function handleCreate(body: AccessionBody) {
    try {
      const created = await accession(body, organizationId);
      setCreatedId(`${created.accessionNo} (${created.accessionIssuer})`);
      setCommandError(undefined);
      reload();
    } catch (failure: unknown) {
      setCommandError(toTransportError(failure));
    }
  }

  function handleFilterChange(next: AccessionState | '') {
    setFilter(next);
  }

  function handleLoadMore() {
    setCursor(nextCursor);
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      {commandError !== undefined && (
        <div data-testid="lab-command-error">{StateView.forTransportError(commandError)}</div>
      )}
      {result.status === 'loading' && items.length === 0 ? (
        <LoadingState />
      ) : result.status === 'error' && items.length === 0 ? (
        StateView.forTransportError(result.error)
      ) : items.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          <AccessionsScreen
            items={items}
            filter={filter}
            onFilterChange={handleFilterChange}
            onCreate={(body) => void handleCreate(body)}
            createdId={createdId}
          />
          {nextCursor !== undefined && (
            <Button onClick={handleLoadMore} disabled={result.status === 'loading'}>
              {labText(locale, 'loadMore')}
            </Button>
          )}
        </>
      )}
    </div>
  );
}
