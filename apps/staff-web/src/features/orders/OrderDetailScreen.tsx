import { Link } from '../../app/router';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { ActionKind, OrderDetail, ReasonAction, TaskEvent } from './domain';
import {
  orderStateKey,
  orderStateLabel,
  ordersText,
  priorityMessageKey,
  timelineActionLabel,
} from './messages';

/** Props for the presentational order detail and response screen. */
export interface OrderDetailScreenProps {
  detail: OrderDetail;
  events: readonly TaskEvent[];
  actions: readonly ActionKind[];
  reasonAction: ReasonAction | undefined;
  reason: string;
  issue: boolean;
  status: string;
  onAccept: () => void;
  onStartReason: (action: ReasonAction) => void;
  onCancelReason: () => void;
  onReasonChange: (value: string) => void;
  onSubmitReason: () => void;
}

/**
 * Render the released order, its append-only task-state timeline and only the
 * recipient responses the API allows for the signed-in role. Reject and
 * clarification collect a durable reason in a form with a linked error summary;
 * the typed value is owned by the page so a conflict never discards it.
 */
export function OrderDetailScreen({
  detail,
  events,
  actions,
  reasonAction,
  reason,
  issue,
  status,
  onAccept,
  onStartReason,
  onCancelReason,
  onReasonChange,
  onSubmitReason,
}: OrderDetailScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  return (
    <section aria-label={ordersText(locale, 'orders')} style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{ordersText(locale, 'orderFields')}</h2>
        <p style={{ marginBlock: 0 }}>
          <b>{ordersText(locale, 'order')}:</b> <code dir="ltr">{detail.orderCode}</code>
          {' · '}
          <b>{ordersText(locale, 'taskState')}:</b>{' '}
          <span data-testid="orders-task-state">
            {ordersText(locale, orderStateKey[detail.state])}
          </span>
          {' · '}
          <b>{ordersText(locale, 'version')}:</b> {detail.version}
        </p>
        <Link to="/orders">{ordersText(locale, 'orders')}</Link>
      </header>
      <dl style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <div>
          <dt>{ordersText(locale, 'patient')}</dt>
          <dd>
            <code dir="ltr">{detail.patientId}</code>
          </dd>
        </div>
        <div>
          <dt>{ordersText(locale, 'encounterRef')}</dt>
          <dd>{detail.encounterRef}</dd>
        </div>
        <div>
          <dt>{ordersText(locale, 'priority')}</dt>
          <dd>{ordersText(locale, priorityMessageKey[detail.priority])}</dd>
        </div>
        <div>
          <dt>{ordersText(locale, 'clinicalQuestion')}</dt>
          <dd>{detail.clinicalQuestion}</dd>
        </div>
        <div>
          <dt>{ordersText(locale, 'destination')}</dt>
          <dd>
            <code dir="ltr">{detail.destinationOrg}</code>
          </dd>
        </div>
        <div>
          <dt>{ordersText(locale, 'workQueue')}</dt>
          <dd>{detail.workQueue}</dd>
        </div>
        <div>
          <dt>{ordersText(locale, 'sourceActor')}</dt>
          <dd>{detail.sourceActor}</dd>
        </div>
        <div>
          <dt>{ordersText(locale, 'responsibleClinician')}</dt>
          <dd>{detail.responsibleClinician}</dd>
        </div>
        <div>
          <dt>{ordersText(locale, 'createdAt')}</dt>
          <dd>{formatInstant(detail.createdAt, 'UTC', locale)}</dd>
        </div>
      </dl>

      <section aria-label={ordersText(locale, 'timeline')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h3>{ordersText(locale, 'timeline')}</h3>
        {events.length === 0 ? (
          <p>{ordersText(locale, 'noHistory')}</p>
        ) : (
          <ol>
            {events.map((event) => (
              <li key={event.id} style={{ display: 'grid', gap: tokens.spacing.sm }}>
                <span>
                  <b>{ordersText(locale, 'eventTransition')}:</b>{' '}
                  {orderStateLabel(locale, event.fromState)} → {orderStateLabel(locale, event.toState)}
                </span>
                <span>
                  <b>{ordersText(locale, 'eventAction')}:</b> {timelineActionLabel(locale, event.action)}
                </span>
                <span>
                  <b>{ordersText(locale, 'eventActor')}:</b> {event.actorId}
                </span>
                <span>
                  <b>{ordersText(locale, 'eventTime')}:</b>{' '}
                  {formatInstant(event.occurredAt, 'UTC', locale)}
                </span>
                <span>
                  <b>{ordersText(locale, 'eventReason')}:</b>{' '}
                  {event.reason ?? ordersText(locale, 'noEventReason')}
                </span>
              </li>
            ))}
          </ol>
        )}
      </section>

      {status !== '' ? (
        <p role="status" data-testid="orders-action-status">
          {status}
        </p>
      ) : null}

      <section aria-label={ordersText(locale, 'actions')} style={{ display: 'grid', gap: tokens.spacing.md }}>
        <h3>{ordersText(locale, 'actions')}</h3>
        <div style={{ display: 'flex', gap: tokens.spacing.sm }}>
          {actions.includes('accept') ? (
            <Button onClick={onAccept}>{ordersText(locale, 'accept')}</Button>
          ) : null}
          {actions.includes('reject') ? (
            <Button onClick={() => onStartReason('reject')}>{ordersText(locale, 'reject')}</Button>
          ) : null}
          {actions.includes('clarify') ? (
            <Button onClick={() => onStartReason('clarify')}>{ordersText(locale, 'clarify')}</Button>
          ) : null}
        </div>
        {actions.length === 0 ? <p>{ordersText(locale, 'noActions')}</p> : null}

        {reasonAction !== undefined ? (
          <Form
            label={ordersText(locale, reasonAction === 'reject' ? 'reject' : 'clarify')}
            submitLabel={ordersText(locale, 'submit')}
            onSubmit={onSubmitReason}
          >
            {issue ? (
              <div role="alert" aria-label={ordersText(locale, 'errorSummaryTitle')}>
                <h4>{ordersText(locale, 'errorSummaryTitle')}</h4>
                <ul>
                  <li>
                    <a href="#orders-reason">{ordersText(locale, 'reasonRequired')}</a>
                  </li>
                </ul>
              </div>
            ) : null}
            <label htmlFor="orders-reason">
              {ordersText(locale, reasonAction === 'reject' ? 'reason' : 'question')}
            </label>
            <textarea
              id="orders-reason"
              value={reason}
              aria-invalid={issue}
              aria-describedby={issue ? 'orders-reason-error' : undefined}
              onChange={(event) => onReasonChange(event.target.value)}
            />
            {issue ? <p id="orders-reason-error">{ordersText(locale, 'reasonRequired')}</p> : null}
            <Button variant="secondary" onClick={onCancelReason}>
              {ordersText(locale, 'cancel')}
            </Button>
          </Form>
        ) : null}
      </section>
    </section>
  );
}
