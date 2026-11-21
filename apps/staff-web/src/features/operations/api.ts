import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import { normalizeQueue } from './domain';
import type { QueueAction, QueueCollection, QueueId, QueueRow } from './types';
import { queueIds } from './types';

/** Query values the connected list endpoints require. */
export interface QueueQuery {
  unitId: string;
  facilityCode: string;
}

/** Resolve a queue's authoritative list URL, including required query values. */
function queueUrl(queue: QueueId, query: QueueQuery): string {
  switch (queue) {
    case 'work-items':
      return '/api/v1/exchange/work-items';
    case 'dead-letters':
      return '/api/v1/delivery/dead-letters';
    case 'overdue-follow-ups':
      return `/api/v1/messaging/instructions/overdue-follow-ups?unitId=${encodeURIComponent(query.unitId)}`;
    case 'duplicate-reviews':
      return '/api/v1/duplicate-reviews';
    case 'clinic-worklist':
      return `/api/v1/clinic/worklist?facilityCode=${encodeURIComponent(query.facilityCode)}`;
  }
}

/** Build a tenant-scoped descriptor for one queue list request. */
export function queueRequest(
  queue: QueueId,
  organizationId: string,
  query: QueueQuery,
): RequestDescriptor {
  const base = buildRequestDescriptor('GET', '/api/v1/facilities', {
    activeOrganization: organizationId,
  });
  return { ...base, url: queueUrl(queue, query) };
}

/** The HTTP target, extra body fields and precondition of one row command. */
export interface ActionTarget {
  url: string;
  body: Record<string, unknown>;
  ifMatch: number | undefined;
}

/**
 * Map a supported queue/action pair to a real API endpoint. Every command is
 * sent once by the caller; the reason is added by `actionDescriptor`.
 */
export function actionTarget(
  queue: QueueId,
  action: QueueAction,
  row: QueueRow,
): ActionTarget {
  switch (queue) {
    case 'work-items':
      return {
        url: `/api/v1/exchange/work-items/${encodeURIComponent(row.id)}/verify-patient`,
        body: { localPatientId: row.detail },
        ifMatch: undefined,
      };
    case 'dead-letters':
      if (action === 'retry') {
        return {
          url: `/api/v1/delivery/events/${encodeURIComponent(row.id)}/retry`,
          body: {},
          ifMatch: 1,
        };
      }
      return {
        url: '/api/v1/delivery/dead-letters/review',
        body: { eventId: row.id, resolution: 'retry' },
        ifMatch: undefined,
      };
    case 'overdue-follow-ups':
      return {
        url: '/api/v1/messaging/instructions/follow-up/resolve',
        body: { instructionId: row.id, revision: 1 },
        ifMatch: undefined,
      };
    case 'duplicate-reviews':
      return {
        url: `/api/v1/duplicate-reviews/${encodeURIComponent(row.id)}/consolidate`,
        body: { confirmedIdentifiers: [], alternateProcess: null },
        ifMatch: 1,
      };
    case 'clinic-worklist':
      if (action === 'assign') {
        return {
          url: `/api/v1/clinic/worklist/${encodeURIComponent(row.id)}/assign`,
          body: { assigneeSubject: 'synthetic.operator' },
          ifMatch: undefined,
        };
      }
      return {
        url: `/api/v1/clinic/worklist/${encodeURIComponent(row.id)}/handoff`,
        body: { toSubject: 'synthetic.supervisor' },
        ifMatch: undefined,
      };
  }
}

/** Build a tenant-scoped, version-guarded descriptor for a row command. */
export function actionDescriptor(
  queue: QueueId,
  action: QueueAction,
  row: QueueRow,
  reason: string,
  organizationId: string,
): RequestDescriptor {
  const target = actionTarget(queue, action, row);
  const meta: RequestMeta = { activeOrganization: organizationId };
  if (target.ifMatch !== undefined) {
    meta.ifMatch = target.ifMatch;
  }
  const base = buildRequestDescriptor('POST', '/api/v1/facilities', meta, {
    ...target.body,
    reason,
  });
  return { ...base, url: target.url };
}

/** Normalise any command rejection to the typed error a state view can show. */
function toTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/**
 * Load all five queues in parallel, keeping each queue's failure local so one
 * unavailable endpoint never hides the other four tabs.
 */
export async function loadQueues(
  organizationId: string,
  query: QueueQuery,
): Promise<QueueCollection> {
  const collection: Partial<QueueCollection> = {};
  await Promise.all(
    queueIds.map(async (queue) => {
      try {
        const raw = await send<readonly unknown[]>(queueRequest(queue, organizationId, query));
        collection[queue] = { status: 'ready', rows: normalizeQueue(queue, raw) };
      } catch (failure) {
        collection[queue] = { status: 'error', error: toTransportError(failure) };
      }
    }),
  );
  return collection as QueueCollection;
}

/**
 * Send one row command. No retry is performed here: a failed command surfaces
 * as an error state and only a new explicit user action can send it again.
 */
export async function runQueueAction(
  queue: QueueId,
  row: QueueRow,
  action: QueueAction,
  reason: string,
  organizationId: string,
): Promise<void> {
  await send(actionDescriptor(queue, action, row, reason, organizationId));
}
