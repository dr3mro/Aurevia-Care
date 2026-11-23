import { Fragment, useLayoutEffect, useRef, useState } from 'react';
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
import type { OrderResultLink, TrackedOrder } from './domain';
import { canAmend, canCancel, cancelOutcome, hasReason, ordersForTenant } from './domain';
import { deliveryModeMessageKey, statusMessageKey, trackingText } from './labels';
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
  pending: boolean;
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
    pending: false,
    announcement: '',
    error: '',
  };
}

/** State after a command settles: the form closes and one outcome is shown. */
function settledState(current: TabState, announcement: string, error: string): TabState {
  return {
    ...current,
    actionOrder: null,
    reason: '',
    reasonError: false,
    pending: false,
    error,
    announcement,
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
  pending: boolean;
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
  pending,
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
        <Button type="button" onClick={onConfirm} disabled={pending}>
          {text(kind)}
        </Button>
        <Button type="button" variant="secondary" onClick={onDiscard} disabled={pending}>
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

/** Props for one order row. */
interface OrderRowProps {
  order: TrackedOrder;
  locale: Locale;
  mayAct: boolean;
  pending: boolean;
  onStart: (order: TrackedOrder, kind: ActionKind) => void;
  onOpenResult: (result: OrderResultLink) => void;
}

/** Render one tenant-scoped order with its status, delivery, result and actions. */
function OrderRow({ order, locale, mayAct, pending, onStart, onOpenResult }: OrderRowProps) {
  const text = (key: TrackingMessageKey): string => trackingText(locale, key);
  return (
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
            {order.delivery.partnerReference === null ? (
              text('notDelivered')
            ) : (
              <>
                {text('partnerReference')}: <bdi dir="ltr">{order.delivery.partnerReference}</bdi>
              </>
            )}
            {order.delivery.deliveredAt !== null ? (
              <>
                {' · '}
                {text('delivered')}:{' '}
                <time dateTime={order.delivery.deliveredAt}>
                  {formatInstant(order.delivery.deliveredAt, order.delivery.timeZone, locale)}
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
            disabled={pending}
            onClick={() => onStart(order, 'cancel')}
          >
            {text('cancel')}
          </button>
        ) : null}{' '}
        {mayAct && canAmend(order) ? (
          <button
            type="button"
            aria-label={`${text('amend')}: ${order.id}`}
            style={controlStyle}
            disabled={pending}
            onClick={() => onStart(order, 'amend')}
          >
            {text('amend')}
          </button>
        ) : null}
      </td>
    </tr>
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

  // The ref mirrors the active tenant synchronously, so a reply arriving after
  // a switch can be discarded before it paints the new tenant's status area.
  const organizationRef = useRef(organizationId);
  useLayoutEffect(() => {
    organizationRef.current = organizationId;
  }, [organizationId]);

  // Reset every field synchronously during render, so a tenant switch can never
  // show the previous tenant's typed reason beside the new tenant's orders.
  if (state.organizationId !== organizationId) {
    setState(initialState(organizationId));
  }

  const visible = ordersForTenant(orders, organizationId);
  const mayAct = canOrder(role);
  const activeOrder = state.actionOrder;
  const text = (key: TrackingMessageKey): string => trackingText(locale, key);

  /** Commit a settled command only if the tenant that sent it is still active. */
  function settle(submittingOrganization: string, update: (current: TabState) => TabState): void {
    if (organizationRef.current !== submittingOrganization) {
      return;
    }
    setState(update);
  }

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
    const submittingOrganization = organizationId;
    // The confirm button is disabled while pending, so React flushes its state
    // before a second click can invoke this handler again.
    setState((current) => ({ ...current, pending: true }));
    void (async () => {
      try {
        if (kind === 'cancel') {
          await onCancel(order, trimmed);
          const key: TrackingMessageKey =
            cancelOutcome(order) === 'cancel-requested'
              ? 'cancelRequestSentNotice'
              : 'cancelledNotice';
          settle(submittingOrganization, (current) =>
            settledState(current, trackingText(locale, key), ''),
          );
        } else {
          await onAmend(order, trimmed);
          settle(submittingOrganization, (current) =>
            settledState(current, trackingText(locale, 'amendNotice'), ''),
          );
        }
      } catch {
        settle(submittingOrganization, (current) =>
          settledState(current, '', trackingText(locale, 'sendError')),
        );
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
                <OrderRow
                  order={order}
                  locale={locale}
                  mayAct={mayAct}
                  pending={state.pending}
                  onStart={startAction}
                  onOpenResult={onOpenResult}
                />
                {activeOrder !== null && activeOrder.id === order.id ? (
                  <tr data-testid="order-action-row">
                    <td colSpan={5}>
                      <ReasonForm
                        order={activeOrder}
                        kind={state.actionKind}
                        reason={state.reason}
                        reasonError={state.reasonError}
                        pending={state.pending}
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
