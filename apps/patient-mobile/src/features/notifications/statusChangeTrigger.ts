/**
 * Turn one authoritative appointment status change into at most one generic
 * patient push notice (PAT-01, ARC-07, OPS-02).
 *
 * The module holds no clinical text and no gateway: the host emits an event,
 * the page supplies verified tenant and patient ids, and every external
 * boundary (authentication, device registry, inbox, gateway, logger, clock and
 * detail fetch) is injected. Detail is resolved through the authentication
 * gate, but the notice sent to the provider is always the fixed generic text.
 */
import { asTransportError } from '../../api/transport';
import type { TransportError } from '../../api/transport';
import { buildGenericNotice } from '../appointments/notifications';
import type { DetailResult } from '../appointments/notifications';
import { tenantKey } from '../appointments/tenancy';
import type {
  AppointmentStatus,
  AuthState,
  DeviceToken,
  GenericNotice,
  LocalId,
  OrganizationId,
} from '../appointments/types';
import { dispatchNotice } from './delivery';
import type { DispatchInput } from './delivery';
import { notificationContent } from './payload';
import type { DeliveryOutcome, InboxItem, NoticeLogger, PushGateway } from './types';

/** One authoritative status change emitted by the signed-in host. */
export interface AppointmentStatusChange {
  readonly organizationId: OrganizationId;
  readonly patientId: LocalId;
  readonly appointmentId: LocalId;
  readonly status: AppointmentStatus;
  /** Opaque tenant-scoped detail reference, never clinical content. */
  readonly detailRef: string;
}

/** Authentication outcome of one change; `none` means no fetch was attempted. */
export type StatusChangeDetail<T> =
  | { readonly kind: 'none' }
  | { readonly kind: 'auth_required' }
  | { readonly kind: 'detail'; readonly value: T };

/** Injected boundaries of one status-change push. */
export interface StatusChangeDependencies<T> {
  /** Verified organization from the signed-in page, never from the event. */
  readonly organizationId: OrganizationId;
  /** Verified linked patient from the signed-in page, never from the event. */
  readonly patientId: LocalId;
  readonly auth: AuthState;
  /** Tenant-keyed registry filtered by `eligibleTokens` during dispatch. */
  readonly devices: ReadonlyMap<string, DeviceToken>;
  /** Authenticated inbox; delivery never filters or empties it. */
  readonly inbox: readonly InboxItem[];
  readonly gateway: PushGateway;
  readonly logger: NoticeLogger;
  /** Fixed server instant, injected so a disposition log is deterministic. */
  readonly at: string;
  /** Authorized detail client; the transport reads the credential itself. */
  readonly fetchDetail: (ref: string) => Promise<T>;
  /** Caller-owned ledger, marked synchronously so a second emit cannot race. */
  readonly dispatched: Set<string>;
  /** Ends the session when detail retrieval reports an expired credential. */
  readonly onSessionExpired: () => void;
}

/** Observable outcome of one emitted status change. */
export interface StatusChangeResult<T> {
  /**
   * `dispatched` sent the notice; `duplicate` was already handled; `rejected`
   * carried unverified tenant or patient ids; `auth_required` had no verified
   * session; `session_expired` ended the session; `fetch_failed` will retry.
   */
  readonly kind:
    | 'dispatched'
    | 'duplicate'
    | 'rejected'
    | 'auth_required'
    | 'session_expired'
    | 'fetch_failed';
  /** The fixed generic notice, whether or not it was sent. */
  readonly notice: GenericNotice;
  /** Detail only when this change was both authorized and fetched. */
  readonly detail: StatusChangeDetail<T>;
  readonly outcomes: readonly DeliveryOutcome[];
  readonly inbox: readonly InboxItem[];
}

/**
 * The injectable host seam that enters the installed page.
 *
 * `subscribe` registers the page's handler and returns an unsubscribe used as
 * the effect cleanup, so a host can emit a status change without the page
 * polling a list endpoint. All other fields are the delivery boundaries.
 */
export interface StatusNoticeSeam {
  /** Register one listener; returns its unsubscribe function. */
  subscribe(listener: (change: AppointmentStatusChange) => void): () => void;
  readonly auth: AuthState;
  readonly devices: ReadonlyMap<string, DeviceToken>;
  readonly inbox: readonly InboxItem[];
  readonly gateway: PushGateway;
  readonly logger: NoticeLogger;
  readonly at: string;
  readonly fetchDetail: (ref: string) => Promise<unknown>;
}

/**
 * Build an unambiguous dedupe key for one status of one patient's appointment.
 *
 * Each identifier is separated by NUL, which cannot occur in a generated local
 * id, so (`a`, `bc`) and (`ab`, `c`) cannot collapse to one key. The verified
 * tenant and patient are part of the key, so a different session never reuses
 * another patient's dedupe entry.
 *
 * @param organizationId verified organization from the signed-in page
 * @param patientId verified linked patient
 * @param appointmentId organization-local appointment identifier
 * @param status authoritative status from the event
 * @returns an opaque key for the caller-owned dispatch ledger
 */
export function statusNoticeKey(
  organizationId: OrganizationId,
  patientId: LocalId,
  appointmentId: LocalId,
  status: string,
): string {
  return `${tenantKey(organizationId, appointmentId)}\u0000${patientId}\u0000${status}`;
}

/**
 * Dispatch at most one content-free notice for a status change.
 *
 * The event's tenant and patient ids are checked against the page's verified
 * ids before any side effect. The key is added before the first await so two
 * emits in one turn cannot both dispatch; every path that does not send removes
 * it again so a later retry is possible. A signed-out event fetches no detail
 * and reaches no (potentially stale) device. A 401 ends the session; any other
 * fetch failure is retryable and is never logged with its error details.
 *
 * @param change authoritative status change emitted by the host
 * @param deps verified ids and injected delivery boundaries
 * @returns the outcome, always carrying the fixed generic notice
 */
export async function triggerStatusNotice<T>(
  change: AppointmentStatusChange,
  deps: StatusChangeDependencies<T>,
): Promise<StatusChangeResult<T>> {
  const notice = buildGenericNotice();
  const skip = (
    kind: StatusChangeResult<T>['kind'],
    detail: StatusChangeDetail<T>,
  ): StatusChangeResult<T> => ({ kind, notice, detail, outcomes: [], inbox: deps.inbox });

  // The event is untrusted: a peer tenant or another patient must not select a
  // device registry or a dedupe entry belonging to the verified session.
  if (change.organizationId !== deps.organizationId || change.patientId !== deps.patientId) {
    return skip('rejected', { kind: 'none' });
  }

  const key = statusNoticeKey(
    deps.organizationId,
    deps.patientId,
    change.appointmentId,
    change.status,
  );
  if (deps.dispatched.has(key)) {
    return skip('duplicate', { kind: 'none' });
  }
  deps.dispatched.add(key);

  let content: { payload: { notice: GenericNotice; detailRef: string }; detail: DetailResult<T> };
  try {
    content = await notificationContent(deps.auth, change.detailRef, deps.fetchDetail);
  } catch (error) {
    deps.dispatched.delete(key);
    // Only the typed kind is inspected. The error object may carry a server
    // correlation id or untrusted details and is never logged here.
    const failure: TransportError = asTransportError(error, 'network');
    if (failure.kind === 'unauthenticated') {
      deps.onSessionExpired();
      return skip('session_expired', { kind: 'none' });
    }
    return skip('fetch_failed', { kind: 'none' });
  }

  // A signed-out patient may have stale active registrations; no detail fetch
  // and no push is allowed before the next verified sign-in.
  if (content.detail.kind === 'auth_required') {
    deps.dispatched.delete(key);
    return skip('auth_required', { kind: 'auth_required' });
  }

  const input: DispatchInput = {
    devices: deps.devices,
    organizationId: deps.organizationId,
    patientId: deps.patientId,
    inbox: deps.inbox,
    notice: content.payload.notice,
    gateway: deps.gateway,
    logger: deps.logger,
    at: deps.at,
  };
  const delivered = await dispatchNotice(input);
  return {
    kind: 'dispatched',
    notice: input.notice,
    detail: { kind: 'detail', value: content.detail.value },
    outcomes: delivered.outcomes,
    inbox: delivered.inbox,
  };
}
