import { useState } from 'react';
import { Button } from '../../components/Button';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName, formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { STATUS_FILTERS, canHandoff, canLinkStudy, canSchedule } from './domain';
import type { ImagingWorklistOrder, StatusFilter } from './domain';
import { modalityMessageKey, radworklistText, statusMessageKey, urgencyMessageKey } from './labels';
import { ScheduleForm } from './ScheduleForm';
import type { ScheduleDraft } from './ScheduleForm';
import { StatusBadge } from './StatusBadge';
import { StudyLinkForm } from './StudyLinkForm';
import type { StudyLinkDraft } from './StudyLinkForm';

/** Props for the presentational performing-site worklist screen. */
export interface RadWorklistScreenProps {
  /** Orders already scoped to the active tenant, site and status filter. */
  orders: readonly ImagingWorklistOrder[];
  organizationId: string;
  site: string;
  filter: StatusFilter;
  onSiteChange: (site: string) => void;
  onFilterChange: (filter: StatusFilter) => void;
  onSchedule: (order: ImagingWorklistOrder, draft: ScheduleDraft) => void;
  onLink: (order: ImagingWorklistOrder, draft: StudyLinkDraft) => void;
  onHandoff: (order: ImagingWorklistOrder) => void;
}

/**
 * Compose the status filter, the accessible worklist table and the actions for
 * the selected order. The page owns loading and commands; this component owns
 * only which row is open.
 */
export function RadWorklistScreen({
  orders,
  organizationId,
  site,
  filter,
  onSiteChange,
  onFilterChange,
  onSchedule,
  onLink,
  onHandoff,
}: RadWorklistScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [selectedId, setSelectedId] = useState<string | undefined>(() => orders[0]?.orderId);
  const text = (key: Parameters<typeof radworklistText>[1]) => radworklistText(locale, key);

  // A reload, filter change or tenant switch supplies a fresh projection; a
  // local id from a previous tenant is never carried forward.
  const [source, setSource] = useState({ organizationId, orders });
  if (source.organizationId !== organizationId || source.orders !== orders) {
    setSource({ organizationId, orders });
    setSelectedId(orders[0]?.orderId);
  }

  const selected = orders.find((order) => order.orderId === selectedId);

  return (
    <section aria-label={text('radworklist')} style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <h2>{text('radworklist')}</h2>
      <p>{text('syntheticNotice')}</p>
      <FormField
        id="rad-performing-site"
        label={text('performingSite')}
        value={site}
        onChange={onSiteChange}
        placeholder="site-rad-main"
      />
      <div
        role="group"
        aria-label={text('worklistStatus')}
        style={{ display: 'flex', gap: tokens.spacing.sm }}
      >
        {STATUS_FILTERS.map((option) => (
          <Button key={option} pressed={filter === option} onClick={() => onFilterChange(option)}>
            {option === 'all' ? text('allStatuses') : text(statusMessageKey[option])}
          </Button>
        ))}
      </div>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
          {text('radworklist')}
        </caption>
        <thead>
          <tr>
            <th scope="col">{text('patient')}</th>
            <th scope="col">{text('order')}</th>
            <th scope="col">{text('urgency')}</th>
            <th scope="col">{text('scheduledAt')}</th>
            <th scope="col">{text('status')}</th>
            <th scope="col">{text('modality')}</th>
            <th scope="col">{text('accession')}</th>
            <th scope="col">{text('actions')}</th>
          </tr>
        </thead>
        <tbody>
          {orders.length === 0 ? (
            <tr>
              <td colSpan={8}>{text('noOrders')}</td>
            </tr>
          ) : (
            orders.map((order) => {
              // Composite key includes the tenant so identical local ids never merge.
              const key = `${order.organizationId}:${order.orderId}`;
              return (
                <tr key={key}>
                  <th scope="row">
                    <MixedScriptText
                      text={displayLocalName({ latinName: order.patientId }, locale)}
                      lang={locale}
                    />
                  </th>
                  <td>{order.orderCode}</td>
                  <td>{text(urgencyMessageKey[order.urgency])}</td>
                  <td>
                    {order.scheduledAt === null ? (
                      text('notRecorded')
                    ) : (
                      <time dateTime={order.scheduledAt}>
                        {formatInstant(order.scheduledAt, 'UTC', locale)}
                      </time>
                    )}
                  </td>
                  <td>
                    <StatusBadge status={order.status} locale={locale} />
                  </td>
                  <td>
                    {order.modality === null
                      ? text('notRecorded')
                      : text(modalityMessageKey[order.modality])}
                  </td>
                  <td>{order.accession ?? text('notRecorded')}</td>
                  <td>
                    <Button onClick={() => setSelectedId(order.orderId)}>
                      {text('openOrder')}
                    </Button>
                  </td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
      {selected === undefined ? (
        <p role="status">{text('selectOrder')}</p>
      ) : (
        <div style={{ display: 'grid', gap: tokens.spacing.md }}>
          <h3>{selected.orderCode}</h3>
          <StatusBadge status={selected.status} locale={locale} />
          {canSchedule(selected) ? (
            <ScheduleForm order={selected} onSubmit={(draft) => onSchedule(selected, draft)} />
          ) : null}
          {canLinkStudy(selected) ? (
            <StudyLinkForm order={selected} onLink={(draft) => onLink(selected, draft)} />
          ) : null}
          {canHandoff(selected) ? (
            <Button onClick={() => onHandoff(selected)}>{text('handoff')}</Button>
          ) : null}
        </div>
      )}
    </section>
  );
}
