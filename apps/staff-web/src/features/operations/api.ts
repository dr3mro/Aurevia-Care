import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor } from '../../api/client';
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

/** The HTTP target and body fields of one row command. */
export interface ActionTarget {
  url: string;
  body: Record<string, unknown>;
}

/**
 * Map a supported queue/action pair to a real API endpoint accepted by the
 * current contract. Commands are sent once by the caller. Every body carries
 * a reason where the endpoint records one, and no precondition is invented:
 * a revision is sent only when the list projection exposed it.
 */
export function actionTarget(
  queue: QueueId,
  action: QueueAction,
  row: QueueRow,
  reason: string,
): ActionTarget {
  switch (queue) {
    case 'work-items':
      // The receiver must send issuer-scoped MRN plus an explicit attestation.
      return {
        url: `/api/v1/exchange/work-items/${encodeURIComponent(row.id)}/verify-patient`,
        body: {
          localPatientId: row.detail,
          localMrn: row.localMrn,
          mrnIssuer: row.mrnIssuer,
          attestation: reason,
        },
      };
    case 'dead-letters':
      // Both actions use the reviewed endpoint: its `resolution` wire keys are
      // the only ones the server parses, and it records the operator reason.
      return {
        url: '/api/v1/delivery/dead-letters/review',
        body: {
          eventId: row.id,
          resolution: action === 'retry' ? 'requeued' : 'resolved',
        },
      };
    case 'overdue-follow-ups':
      return {
        url: '/api/v1/messaging/instructions/follow-up/resolve',
        body: { instructionId: row.id, revision: row.version },
      };
    case 'duplicate-reviews':
      // Open a review ticket for the pair. Consolidation is deliberately not
      // offered: it needs a ticket id and independent identifier confirmation.
      return {
        url: '/api/v1/duplicate-reviews',
        body: { survivorId: row.survivorId, duplicateId: row.duplicateId },
      };
    case 'clinic-worklist':
      if (action === 'assign') {
        return {
          url: `/api/v1/clinic/worklist/${encodeURIComponent(row.id)}/assign`,
          body: { assigneeSubject: 'synthetic.operator' },
          };
      }
      return {
        url: `/api/v1/clinic/worklist/${encodeURIComponent(row.id)}/handoff`,
        body: { toSubject: 'synthetic.supervisor' },
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
  const target = actionTarget(queue, action, row, reason);
  const base = buildRequestDescriptor('POST', '/api/v1/facilities', {
    activeOrganization: organizationId,
  }, { ...target.body, reason });
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
