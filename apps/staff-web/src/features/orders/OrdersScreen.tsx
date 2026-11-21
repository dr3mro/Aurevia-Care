import { Link } from '../../app/router';
import { Button } from '../../components/Button';
import { useI18n } from '../../i18n/I18nProvider';
import { formatInstant } from '../../i18n/format';
import { useTheme } from '../../theme/ThemeProvider';
import { ORDER_STATES } from './domain';
import type { OrderRow } from './domain';
import { orderStateKey, ordersText, priorityMessageKey } from './messages';

/** Props for the presentational order worklist. */
export interface OrdersScreenProps {
  rows: readonly OrderRow[];
  status: string;
  onStatusChange: (value: string) => void;
  hasMore: boolean;
  onLoadMore: () => void;
}

/**
 * Render the order worklist: a work-item state filter and a real table whose
 * rows are keyed by the opaque order id, with a detail link as the row action.
 * The table never contains clinical free text or patient names from the URL.
 */
export function OrdersScreen({
  rows,
  status,
  onStatusChange,
  hasMore,
  onLoadMore,
}: OrdersScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <section aria-label={ordersText(locale, 'orders')} style={{ display: 'grid', gap: tokens.spacing.md }}>
      <p>{ordersText(locale, 'syntheticNotice')}</p>
      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <label htmlFor="orders-status">{ordersText(locale, 'status')}</label>
        <select
          id="orders-status"
          value={status}
          onChange={(event) => onStatusChange(event.target.value)}
        >
          <option value="">{ordersText(locale, 'allStatuses')}</option>
          {ORDER_STATES.map((state) => (
            <option key={state} value={state}>
              {ordersText(locale, orderStateKey[state])}
            </option>
          ))}
        </select>
      </div>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start', padding: tokens.spacing.sm }}>
          {ordersText(locale, 'orders')}
        </caption>
        <thead>
          <tr>
            <th scope="col">{ordersText(locale, 'order')}</th>
            <th scope="col">{ordersText(locale, 'patient')}</th>
            <th scope="col">{ordersText(locale, 'priority')}</th>
            <th scope="col">{ordersText(locale, 'destination')}</th>
            <th scope="col">{ordersText(locale, 'createdAt')}</th>
            <th scope="col">{ordersText(locale, 'actions')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id}>
              <th scope="row">{row.orderCode}</th>
              <td>
                <code dir="ltr">{row.patientId}</code>
              </td>
              <td>{ordersText(locale, priorityMessageKey[row.priority])}</td>
              <td>
                <code dir="ltr">{row.destinationOrg}</code>
              </td>
              <td>{formatInstant(row.createdAt, 'UTC', locale)}</td>
              <td>
                <Link to={`/orders/${encodeURIComponent(row.id)}`}>
                  {ordersText(locale, 'detail')}
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {hasMore ? <Button onClick={onLoadMore}>{ordersText(locale, 'loadMore')}</Button> : null}
    </section>
  );
}
