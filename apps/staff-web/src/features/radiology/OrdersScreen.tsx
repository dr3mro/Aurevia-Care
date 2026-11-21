/** Presentational list of imaging orders. It never calls the network. */

import { Link } from '../../app/router';
import { MixedScriptText } from '../../components/MixedScriptText';
import { formatCivilDate, displayLocalName } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { ImagingOrder } from './domain';
import { radiologyText, stateMessageKey, urgencyMessageKey } from './messages';

/** Props for the imaging order list. */
export interface OrdersScreenProps {
  orders: readonly ImagingOrder[];
  locale: Locale;
}

/** Render a captioned, keyboard-navigable table with a real link per row. */
export function OrdersScreen({ orders, locale }: OrdersScreenProps) {
  const { tokens } = useTheme();
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <caption style={{ textAlign: 'start', padding: tokens.spacing.sm }}>
        {radiologyText(locale, 'orders')}
      </caption>
      <thead>
        <tr>
          <th scope="col">{radiologyText(locale, 'patient')}</th>
          <th scope="col">{radiologyText(locale, 'urgency')}</th>
          <th scope="col">{radiologyText(locale, 'modality')}</th>
          <th scope="col">{radiologyText(locale, 'state')}</th>
          <th scope="col">{radiologyText(locale, 'accession')}</th>
          <th scope="col">{radiologyText(locale, 'scheduled')}</th>
          <th scope="col">{radiologyText(locale, 'actions')}</th>
        </tr>
      </thead>
      <tbody>
        {orders.map((order) => (
          <tr key={order.id}>
            <td>
              <MixedScriptText text={displayLocalName(order.patientName, locale)} lang={locale} />{' '}
              <code dir="ltr">{order.patientId}</code>
            </td>
            <td>{radiologyText(locale, urgencyMessageKey[order.urgency])}</td>
            <td>{order.modality ?? '—'}</td>
            <td>{radiologyText(locale, stateMessageKey[order.state])}</td>
            <td dir="ltr">{order.accessionNo ?? '—'}</td>
            <td>
              {order.scheduledAt === null
                ? '—'
                : formatCivilDate(order.scheduledAt.slice(0, 10), locale)}
            </td>
            <td>
              <Link to={`/imaging/orders/${encodeURIComponent(order.id)}`}>
                {radiologyText(locale, 'orderDetail')}
              </Link>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
