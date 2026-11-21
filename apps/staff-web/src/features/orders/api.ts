/**
 * Real-API client for the order worklist, order detail, XCH-02 history and the
 * three XCH-03 recipient responses (XCH-01/02/03, ARC-03/04).
 *
 * Every descriptor carries the active organization through the shared request
 * builder. Commands carry a caller-owned idempotency key and the version read
 * by the user, so a retry is deduplicated and a stale read is rejected by the
 * server. Response text is never copied into an error or a log.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type { OrderDetail, OrderRow, OrderState, TaskEvent } from './domain';

/** Order projection returned by `GET /api/v1/orders`. */
export interface OrderWire {
  id: string;
  version: number;
  patientId: string;
  encounterRef: string;
  orderCode: string;
  priority: 'routine' | 'urgent' | 'stat';
  clinicalQuestion: string;
  destinationOrg: string;
  sourceActor: string;
  responsibleClinician: string;
  workQueue: string;
  authorizationId: string;
  snapshotId: string;
  snapshotFingerprint: string;
  disclosureId: string;
  workItemId: string;
  createdAt: string;
}

/** One cursor-paginated page of released orders. */
export interface OrderPageResponse {
  items: readonly OrderWire[];
  nextCursor: string | null;
}

/** One append-only XCH-03 response-history entry. */
export interface ExchangeEventWire {
  id: string;
  orderVersion: number;
  action: string;
  fromState: string;
  toState: string;
  reason: string | null;
  actorId: string;
  occurredAt: string;
}

/** Current recipient projection returned by a response command. */
export interface ExchangeOrderWire {
  id: string;
  version: number;
  orderCode: string;
  state: OrderState;
  revision: number;
  localPatientId: string;
  assignee: string | null;
  reason: string | null;
  question: string | null;
  permittedPurpose: string | null;
  retentionPolicy: string | null;
}

/** Query supported by `GET /api/v1/orders` (READY-16). */
export interface OrderListQuery {
  status?: string | undefined;
  cursor?: string | undefined;
  limit?: number | undefined;
}

/**
 * Bounded accept metadata required by the XCH-03 trust boundary. The purpose
 * is a bounded code and the policy is one of the server's approved values; no
 * site configuration endpoint is in this feature's scope, so these defaults
 * are explicit and reviewable rather than inferred from the payload.
 */
const PERMITTED_PURPOSE = 'TREATMENT';
const RETENTION_POLICY = 'STANDARD';

/** Build a GET descriptor with only the query parameters that were supplied. */
function listDescriptor(query: OrderListQuery, organizationId: string): RequestDescriptor {
  const base = buildRequestDescriptor('GET', '/api/v1/orders', {
    activeOrganization: organizationId,
  });
  const params = new URLSearchParams();
  if (query.status !== undefined) {
    params.set('status', query.status);
  }
  if (query.cursor !== undefined) {
    params.set('cursor', query.cursor);
  }
  if (query.limit !== undefined) {
    params.set('limit', String(query.limit));
  }
  const suffix = params.toString();
  return suffix === '' ? base : { ...base, url: `${base.url}?${suffix}` };
}

/**
 * Build a descriptor for a path not yet published in the generated contract.
 * Tenant, correlation, version and idempotency headers stay owned by the
 * shared request builder; only the URL is substituted.
 */
function substitutedDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown> | undefined,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, '/api/v1/orders', meta, body);
  return { ...base, url: path };
}

/** Fetch one page of released orders in the active organization. */
export async function listOrders(
  query: OrderListQuery,
  organizationId: string,
): Promise<OrderPageResponse> {
  return send<OrderPageResponse>(listDescriptor(query, organizationId));
}

/** Read one released order by its opaque identifier. */
export async function getOrder(id: string, organizationId: string): Promise<OrderWire> {
  return send<OrderWire>(
    substitutedDescriptor('GET', `/api/v1/orders/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Read the append-only XCH-02/03 response history of one order. */
export async function getOrderHistory(
  id: string,
  organizationId: string,
): Promise<readonly ExchangeEventWire[]> {
  return send<readonly ExchangeEventWire[]>(
    substitutedDescriptor('GET', `/api/v1/xch03/orders/${encodeURIComponent(id)}/history`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Accept a requested order against the locally verified patient association. */
export async function acceptOrder(
  id: string,
  version: number,
  organizationId: string,
  idempotencyKey: string,
  localPatientId: string,
): Promise<ExchangeOrderWire> {
  return send<ExchangeOrderWire>(
    substitutedDescriptor(
      'POST',
      `/api/v1/xch03/orders/${encodeURIComponent(id)}/accept`,
      { activeOrganization: organizationId, ifMatch: version, idempotencyKey },
      {
        localPatientId,
        permittedPurpose: PERMITTED_PURPOSE,
        retentionPolicy: RETENTION_POLICY,
      },
    ),
  );
}

/** Reject a requested order with a durable reason. */
export async function rejectOrder(
  id: string,
  version: number,
  organizationId: string,
  idempotencyKey: string,
  reason: string,
): Promise<ExchangeOrderWire> {
  return send<ExchangeOrderWire>(
    substitutedDescriptor(
      'POST',
      `/api/v1/xch03/orders/${encodeURIComponent(id)}/reject`,
      { activeOrganization: organizationId, ifMatch: version, idempotencyKey },
      { reason },
    ),
  );
}

/** Request clarification from the sender with a durable question. */
export async function clarifyOrder(
  id: string,
  version: number,
  organizationId: string,
  idempotencyKey: string,
  question: string,
): Promise<ExchangeOrderWire> {
  return send<ExchangeOrderWire>(
    substitutedDescriptor(
      'POST',
      `/api/v1/xch03/orders/${encodeURIComponent(id)}/clarify`,
      { activeOrganization: organizationId, ifMatch: version, idempotencyKey },
      { question },
    ),
  );
}

/** Project one wire order into the list-row display type. */
export function toOrderRow(wire: OrderWire): OrderRow {
  return {
    id: wire.id,
    orderCode: wire.orderCode,
    patientId: wire.patientId,
    priority: wire.priority,
    destinationOrg: wire.destinationOrg,
    createdAt: wire.createdAt,
    version: wire.version,
  };
}

/** Project one wire order and its resolved state into the detail display type. */
export function toOrderDetail(
  wire: OrderWire,
  state: OrderState,
  organizationId: string,
): OrderDetail {
  return {
    ...toOrderRow(wire),
    organizationId,
    encounterRef: wire.encounterRef,
    clinicalQuestion: wire.clinicalQuestion,
    sourceActor: wire.sourceActor,
    responsibleClinician: wire.responsibleClinician,
    workQueue: wire.workQueue,
    state,
  };
}

/** Project one history event into the timeline display type. */
export function toTaskEvent(wire: ExchangeEventWire): TaskEvent {
  return {
    id: wire.id,
    orderVersion: wire.orderVersion,
    action: wire.action,
    fromState: wire.fromState,
    toState: wire.toState,
    reason: wire.reason,
    actorId: wire.actorId,
    occurredAt: wire.occurredAt,
  };
}

/** Normalise an unexpected rejection to the typed error the state views show. */
export function asOrdersTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}
