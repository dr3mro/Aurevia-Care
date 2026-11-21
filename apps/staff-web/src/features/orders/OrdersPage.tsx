import { useEffect, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { useApi } from '../../api/useApi';
import { EmptyState, ErrorState, LoadingState, StateView } from '../../components/StateViews';
import { OrdersScreen } from './OrdersScreen';
import { listOrders, toOrderRow } from './api';
import type { OrderPageResponse } from './api';
import type { OrderRow } from './domain';

/**
 * API-backed order worklist for `/orders`. The status filter and the cursor
 * identify one request; pages are appended, and a tenant switch clears every
 * previously loaded row before the replacement request can respond (ARC-02).
 */
export function OrdersPage() {
  const organizationId = useActiveOrganization();
  const [status, setStatus] = useState('');
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [rows, setRows] = useState<readonly OrderRow[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);

  const result = useApi<OrderPageResponse>(
    () => listOrders({ status: status === '' ? undefined : status, cursor }, organizationId),
    [status, cursor],
  );

  // Append each resolved page once. The dependency is the page object, not the
  // result wrapper, so an unrelated render never duplicates rows.
  useEffect(() => {
    if (result.status !== 'ready') {
      return;
    }
    const page = result.data;
    setRows((current) => [...current, ...page.items.map(toOrderRow)]);
    setNextCursor(page.nextCursor ?? undefined);
  }, [result.status, result.data]);

  // A tenant switch invalidates every accumulated page and the active cursor.
  useEffect(() => {
    setCursor(undefined);
    setRows([]);
    setNextCursor(undefined);
  }, [organizationId]);

  function handleStatusChange(value: string) {
    setStatus(value);
    setCursor(undefined);
    setRows([]);
    setNextCursor(undefined);
  }

  function handleLoadMore() {
    setCursor(nextCursor);
  }

  // Loaded rows stay on screen while the next page is requested or a later
  // refresh fails, so pagination never blanks a worklist under the user.
  if (rows.length > 0) {
    return (
      <>
        {result.status === 'error' ? (
          <div data-testid="orders-page-error">
            <ErrorState correlationId={result.error.correlationId} onRetry={result.reload} />
          </div>
        ) : null}
        <OrdersScreen
          rows={rows}
          status={status}
          onStatusChange={handleStatusChange}
          hasMore={nextCursor !== undefined}
          onLoadMore={handleLoadMore}
        />
      </>
    );
  }
  if (result.status === 'loading') {
    return <LoadingState />;
  }
  if (result.status === 'error') {
    return <div>{StateView.forTransportError(result.error)}</div>;
  }
  return <EmptyState />;
}
