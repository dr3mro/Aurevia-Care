import { Fragment, useState } from 'react';
import type { Locale } from '../../../i18n/messages';
import { Button } from '../../../components/Button';
import { FormField } from '../../../components/FormField';
import { MixedScriptText } from '../../../components/MixedScriptText';
import { formatInstant } from '../../../i18n/format';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import type { Role } from '../../../app/navigation';
import { canOrder } from '../entry/domain';
import { entryText } from '../entry/messages';
import type {
  OrderResultLink,
  TrackedOrder,
} from './domain';
import { canAmend, canCancel, cancelOutcome, hasReason, ordersForTenant } from './domain';
import {
  deliveryModeMessageKey,
  statusMessageKey,
  trackingText,
} from './labels';
import type { TrackingMessageKey } from './labels';

/** The two reason-backed commands available on an active order. */
type ActionKind = 'cancel' | 'amend';

/** Props for the chart-embedded orders list. All data and callbacks are synthetic. */
export interface OrdersTabProps {
  organizationId: string;
  orders: readonly TrackedOrder[];
  /** Role of the signed-in user; only an ordering clinician may cancel or amend. */
  role: Role;
  onCancel: (order: TrackedOrder, reason: string) => Promise<void>;
  onAmend: (order: TrackedOrder, reason: string) => Promise<void>;
  onOpenResult: (result: OrderResultLink) => void;
}

/** Mutable state owned by one active tenant; a switch resets it during render. */
interface TabState {
  organizationId: string;
  actionOrder: TrackedOrder | null;
  actionKind: ActionKind;
  reason: string;
  reasonError: boolean;
  announcement: string;
  error: string;
}

/** Build fresh state so a tenant switch never paints another tenant's reason. */
function initialState(organizationId: string): TabState {
  return {
    organizationId,
    actionOrder: null,
    actionKind: 'cancel',
    reason: '',
    reasonError: false,
    announcement: '',
    error: '',
  };
}

const controlStyle = {
  minHeight: '32px',
  padding: '8px',
  border: '1px solid currentColor',
  background: 'transparent',
  color: 'inherit',
  font: 'inherit',
} as const;

/** Props for the inline reason form shown under the order being changed. */
interface ReasonFormProps {
  order: TrackedOrder;
  kind: ActionKind;
  reason: string;
  reasonError: boolean;
  locale: Locale;
  onReasonChange: (value: string) => void;
  onConfirm: () => void;
  onDiscard: () => void;
}

/** Render the audited reason step for one cancel or amend, with a safe discard. */
function ReasonForm({
  order,
  kind,
  reason,
  reasonError,
  locale,
  onReasonChange,
  onConfirm,
  onDiscard,
}: ReasonFormProps) {
  const { tokens } = useTheme();
  const text = (key: TrackingMessageKey): string => trackingText(locale, key);
  return (
    <div data-testid="order-action-form" style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <p style={{ marginBlock: 0 }}>
        <b>{text(kind)}:</b> <MixedScriptText text={order.display} lang={locale} />{' '}
        <code dir="ltr">{order.id}</code>
      </p>
      <FormField
        id={`reason-${order.id}`}
        label={`${text('reason')} (${text('required')})`}
        value={reason}
        onChange={onReasonChange}
        {...(reasonError ? { error: text('reasonRequired') } : {})}
      />
      <div style={{ display: 'flex', gap: tokens.spacing.sm }}>
        <Button type="button" onClick={onConfirm}>
          {text(kind)}
        </Button>
        <Button type="button" variant="secondary" onClick={onDiscard}>
          {text('discard')}
        </Button>
      </div>
    </div>
  );
}

/** Props for the isolated result-link control. */
interface ResultLinkProps {
  result: OrderResultLink;
  locale: Locale;
  onOpen: (result: OrderResultLink) => void;
}

/** Render a real button that opens the linked result through an injected callback. */
function ResultLink({ result, locale, onOpen }: ResultLinkProps) {
  const label = trackingText(locale, 'openResult');
  return (
    <button
      type="button"
      aria-label={`${label}: ${result.label}`}
      style={controlStyle}
      onClick={() => onOpen(result)}
    >
      {label}
    </button>
  );
}

/**
 * Orders tab for the patient chart (LAB-01, RAD-01, XCH-03, WEB-09).
 *
 * Each tenant-scoped order shows its status, delivery evidence and a result
 * link when one exists. Cancel and amend require a typed reason; cancelling an
 * order the partner already accepted announces a request rather than claiming
 * the order was cancelled. The component performs no network call: commands and
 * result navigation arrive as injected callbacks.
 */
export function OrdersTab(props: OrdersTabProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [state, setState] = useState<TabState>(() => initialState(props.organizationId));
  const { organizationId, orders, role, onCancel, onAmend, onOpenResult } = props;

  // Reset every field synchronously during render, so a tenant switch can never
  // show the previous tenant's typed reason beside the new tenant's orders.
  if (state.organizationId !== organizationId) {
    setState(initialState(organizationId));
  }

  const visible = ordersForTenant(orders, organizationId);
  const mayAct = canOrder(role);
  const activeOrder = state.actionOrder;
  const text = (key: TrackingMessageKey): string => trackingText(locale, key);

  function startAction(order: TrackedOrder, kind: ActionKind): void {
    setState((current) => ({
      ...current,
      actionOrder: order,
      actionKind: kind,
      reason: '',
      reasonError: false,
      announcement: '',
      error: '',
    }));
  }

  function updateReason(value: string): void {
    setState((current) => ({ ...current, reason: value, reasonError: false }));
  }

  function discardAction(): void {
    setState((current) => ({
      ...current,
      actionOrder: null,
      reason: '',
      reasonError: false,
    }));
  }

  function submitAction(order: TrackedOrder, kind: ActionKind, reason: string): void {
    if (!hasReason(reason)) {
      setState((current) => ({ ...current, reasonError: true }));
      return;
    }
    const trimmed = reason.trim();
    void (async () => {
      try {
        if (kind === 'cancel') {
          await onCancel(order, trimmed);
          const key: TrackingMessageKey =
            cancelOutcome(order) === 'cancel-requested'
              ? 'cancelRequestSentNotice'
              : 'cancelledNotice';
          setState((current) => ({
            ...current,
            actionOrder: null,
            reason: '',
            reasonError: false,
            error: '',
            announcement: trackingText(locale, key),
          }));
        } else {
          await onAmend(order, trimmed);
          setState((current) => ({
            ...current,
            actionOrder: null,
            reason: '',
            reasonError: false,
            error: '',
            announcement: trackingText(locale, 'amendNotice'),
          }));
        }
      } catch {
        setState((current) => ({
          ...current,
          actionOrder: null,
          reason: '',
          reasonError: false,
          error: trackingText(locale, 'sendError'),
          announcement: '',
        }));
      }
    })();
  }

  return (
    <section aria-label={text('ordersTab')} style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <h2>{text('ordersTab')}</h2>
        <p style={{ marginBlock: 0 }}>{text('syntheticNotice')}</p>
      </header>
      <p role="status" data-testid="tracking-status">
        {state.announcement}
      </p>
      {state.error !== '' ? (
        <p role="alert" data-testid="tracking-error">
          {state.error}
        </p>
      ) : null}
      {visible.length === 0 ? (
        <p>{text('noOrders')}</p>
      ) : (
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
            {text('ordersTab')}
          </caption>
          <thead>
            <tr>
              <th scope="col">{text('colOrder')}</th>
              <th scope="col">{text('colStatus')}</th>
              <th scope="col">{text('colDelivery')}</th>
              <th scope="col">{text('colResult')}</th>
              <th scope="col">{text('colActions')}</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((order) => (
              <Fragment key={`${organizationId}:${order.id}`}>
                <tr data-testid={`order-row-${order.id}`}>
                  <th scope="row">
                    <MixedScriptText text={order.display} lang={locale} /> <code dir="ltr">{order.id}</code>
                  </th>
                  <td data-testid={`order-status-${order.id}`}>
                    <MixedScriptText text={text(statusMessageKey(order.status))} lang={locale} />
                    {order.cancelRequested ? <span> {text('cancelRequested')}</span> : null}
                  </td>
                  <td data-testid={`order-delivery-${order.id}`}>
                    {order.delivery === null ? (
                      text('notDelivered')
                    ) : (
                      <>
                        <MixedScriptText
                          text={entryText(locale, deliveryModeMessageKey(order.delivery.mode))}
                          lang={locale}
                        />
                        {' · '}
                        <bdi dir="ltr">
                          {order.delivery.partnerReference ?? text('notDelivered')}
                        </bdi>
                        {order.delivery.deliveredAt !== null ? (
                          <>
                            {' · '}
                            <time dateTime={order.delivery.deliveredAt}>
                              {formatInstant(
                                order.delivery.deliveredAt,
                                order.delivery.timeZone,
                                locale,
                              )}
                            </time>
                          </>
                        ) : null}
                      </>
                    )}
                  </td>
                  <td>
                    {order.result === null ? (
                      text('noResult')
                    ) : (
                      <ResultLink result={order.result} locale={locale} onOpen={onOpenResult} />
                    )}
                  </td>
                  <td>
                    {mayAct && canCancel(order) ? (
                      <button
                        type="button"
                        aria-label={`${text('cancel')}: ${order.id}`}
                        style={controlStyle}
                        onClick={() => startAction(order, 'cancel')}
                      >
                        {text('cancel')}
                      </button>
                    ) : null}{' '}
                    {mayAct && canAmend(order) ? (
                      <button
                        type="button"
                        aria-label={`${text('amend')}: ${order.id}`}
                        style={controlStyle}
                        onClick={() => startAction(order, 'amend')}
                      >
                        {text('amend')}
                      </button>
                    ) : null}
                  </td>
                </tr>
                {activeOrder !== null && activeOrder.id === order.id ? (
                  <tr data-testid="order-action-row">
                    <td colSpan={5}>
                      <ReasonForm
                        order={activeOrder}
                        kind={state.actionKind}
                        reason={state.reason}
                        reasonError={state.reasonError}
                        locale={locale}
                        onReasonChange={updateReason}
                        onConfirm={() => submitAction(activeOrder, state.actionKind, state.reason)}
                        onDiscard={discardAction}
                      />
                    </td>
                  </tr>
                ) : null}
              </Fragment>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
