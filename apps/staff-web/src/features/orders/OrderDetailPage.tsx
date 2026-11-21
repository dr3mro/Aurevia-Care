import { useEffect, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import type { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { useRoute } from '../../app/router';
import type { Role } from '../../app/navigation';
import { useRole } from '../../app/useRole';
import { ConflictState, LoadingState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import {
  acceptOrder,
  asOrdersTransportError,
  clarifyOrder,
  getOrder,
  getOrderHistory,
  rejectOrder,
  toOrderDetail,
  toTaskEvent,
} from './api';
import type { ExchangeEventWire, OrderWire } from './api';
import {
  availableActions,
  currentState,
  idempotencyKeyFor,
  isConflict,
  validateReason,
  versionFor,
} from './domain';
import type { ActionKey, ActionKind, OrderDetail, ReasonAction, TaskEvent } from './domain';
import { ordersText } from './messages';
import { OrderDetailScreen } from './OrderDetailScreen';

/** One order and its append-only response history. */
type DetailData = { order: OrderWire; history: readonly ExchangeEventWire[] };

/** Props for the API-backed order detail page. */
export interface OrderDetailPageProps {
  /** Role used by previews and tests; production uses the session role. */
  roleOverride?: Role | undefined;
}

/**
 * API-backed detail for `/orders/:id`. The order and its XCH-02/03 history are
 * loaded together; the newest transition supplies the current task state. Each
 * response carries the read version as `If-Match` and a key that is stable for
 * retries of the same action and fresh for a different action. A 409/412 shows
 * the conflict state without clearing the typed reason.
 */
export function OrderDetailPage({ roleOverride }: OrderDetailPageProps) {
  const organizationId = useActiveOrganization();
  const sessionRole = useRole();
  const role = roleOverride ?? sessionRole;
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const { params } = useRoute();
  const id = params.id ?? '';

  const result = useApi<DetailData>(
    () =>
      Promise.all([getOrder(id, organizationId), getOrderHistory(id, organizationId)]).then(
        ([order, history]) => ({ order, history }),
      ),
    [id, organizationId],
  );

  const [detail, setDetail] = useState<OrderDetail | undefined>(undefined);
  const [events, setEvents] = useState<readonly TaskEvent[]>([]);
  const [reasonAction, setReasonAction] = useState<ReasonAction | undefined>(undefined);
  const [reason, setReason] = useState('');
  const [issue, setIssue] = useState(false);
  const [keyState, setKeyState] = useState<ActionKey | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [conflict, setConflict] = useState(false);
  const [comparing, setComparing] = useState(false);
  const [status, setStatus] = useState('');

  // Re-seed the read model from every authoritative response, including after
  // a successful command reload. Local drafts never survive a tenant switch.
  useEffect(() => {
    if (result.status !== 'ready') {
      return;
    }
    const { order, history } = result.data;
    setDetail(toOrderDetail(order, currentState(history), organizationId));
    setEvents(history.map(toTaskEvent));
    setReasonAction(undefined);
    setReason('');
    setIssue(false);
    setKeyState(undefined);
    setCommandError(undefined);
    setConflict(false);
    setComparing(false);
    setStatus('');
  }, [result.status, result.data, organizationId]);

  // A typed reason is unsaved work (WEB-09). The in-app "Orders" link cannot be
  // intercepted from this feature, but the browser-level navigation guard keeps
  // a back/close/reload from silently discarding it.
  useEffect(() => {
    if (reason.trim().length === 0) {
      return undefined;
    }
    const warn = (event: BeforeUnloadEvent): void => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [reason]);

  async function runCommand(next: ActionKind, typed: string | undefined): Promise<void> {
    const payload = typed ?? '';
    const key = idempotencyKeyFor(keyState, next, payload, () => crypto.randomUUID());
    setKeyState(key);
    setStatus('');
    setCommandError(undefined);
    setConflict(false);
    try {
      const version = versionFor(events, detail!.version);
      if (next === 'accept') {
        await acceptOrder(id, version, organizationId, key.key, detail!.patientId);
      } else if (next === 'reject') {
        await rejectOrder(id, version, organizationId, key.key, typed!);
      } else {
        await clarifyOrder(id, version, organizationId, key.key, typed!);
      }
      setReasonAction(undefined);
      setReason('');
      setIssue(false);
      setStatus(ordersText(locale, 'commandSent'));
      result.reload();
    } catch (failure: unknown) {
      const error = asOrdersTransportError(failure);
      setCommandError(error);
      setConflict(isConflict(error.kind));
    }
  }

  function handleStartReason(next: ReasonAction): void {
    setReasonAction(next);
    setReason('');
    setIssue(false);
    setKeyState(undefined);
    setComparing(false);
    setStatus('');
  }

  function handleCopy(): void {
    // The typed rationale is the only work at risk on a stale version; copy it
    // before reloading so the clinician can paste it into the fresh form.
    void navigator.clipboard?.writeText(reason);
  }

  function handleCancelReason(): void {
    setReasonAction(undefined);
    setReason('');
    setIssue(false);
  }

  function handleSubmitReason(): void {
    if (!validateReason(reason)) {
      setIssue(true);
      return;
    }
    void runCommand(reasonAction!, reason);
  }

  if (detail === undefined) {
    if (result.status === 'error') {
      return <>{StateView.forTransportError(result.error)}</>;
    }
    return <LoadingState />;
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      {commandError !== undefined && !conflict ? (
        <div>{StateView.forTransportError(commandError)}</div>
      ) : null}
      {conflict ? (
        <ConflictState
          onCompare={() => setComparing(true)}
          onReload={result.reload}
          onCopy={handleCopy}
        />
      ) : null}
      {comparing ? (
        <section data-testid="orders-compare" style={{ display: 'grid', gap: tokens.spacing.sm }}>
          <h3>{ordersText(locale, 'compareTitle')}</h3>
          <p>
            <b>{ordersText(locale, 'yourReason')}:</b> {reason}
          </p>
          <p>{ordersText(locale, 'compareHint')}</p>
        </section>
      ) : null}
      <OrderDetailScreen
        detail={detail}
        events={events}
        actions={availableActions(detail.state, role)}
        reasonAction={reasonAction}
        reason={reason}
        issue={issue}
        status={status}
        onAccept={() => void runCommand('accept', undefined)}
        onStartReason={handleStartReason}
        onCancelReason={handleCancelReason}
        onReasonChange={(value) => {
          setReason(value);
          setIssue(false);
        }}
        onSubmitReason={handleSubmitReason}
      />
    </div>
  );
}
