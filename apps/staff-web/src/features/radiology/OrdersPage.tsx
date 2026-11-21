/** API-backed imaging order list with cursor paging and WEB-09 state views. */

import { useEffect, useMemo, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { useApi } from '../../api/useApi';
import { Button } from '../../components/Button';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import type { ImagingOrderPageResponse, ImagingOrderResponse } from './api';
import { listImagingOrders, toImagingOrder } from './api';
import { OrdersScreen } from './OrdersScreen';
import { radiologyText } from './messages';

/** Load pages of imaging orders for the active tenant. */
export function OrdersPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<readonly ImagingOrderResponse[]>([]);
  const [nextCursor, setNextCursor] = useState<string | undefined>(undefined);

  const result = useApi<ImagingOrderPageResponse>(
    () => listImagingOrders({ cursor }, organizationId),
    [cursor],
  );

  useEffect(() => {
    if (result.status !== 'ready') {
      return;
    }
    setAccumulated((current) => [...current, ...result.data.items]);
    setNextCursor(result.data.nextCursor ?? undefined);
  }, [result.status, result.data]);

  useEffect(() => {
    setCursor(undefined);
    setAccumulated([]);
    setNextCursor(undefined);
  }, [organizationId]);

  const orders = useMemo(
    () => accumulated.map(toImagingOrder),
    [accumulated],
  );

  if (result.status === 'loading' && accumulated.length === 0) {
    return <LoadingState />;
  }
  if (result.status === 'error' && accumulated.length === 0) {
    return <>{StateView.forTransportError(result.error)}</>;
  }
  if (accumulated.length === 0) {
    return <EmptyState />;
  }
  return (
    <div>
      <OrdersScreen orders={orders} locale={locale} />
      {nextCursor !== undefined && (
        <Button onClick={() => setCursor(nextCursor)} disabled={result.status === 'loading'}>
          {radiologyText(locale, 'loadMore')}
        </Button>
      )}
    </div>
  );
}
