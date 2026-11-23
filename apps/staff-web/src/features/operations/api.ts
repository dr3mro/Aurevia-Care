/**
 * Real-API client for the ADM-02 operational queues.
 *
 * The queue routes are not published in the generated contract yet, so a real
 * contract path supplies the shared tenant, correlation, idempotency and version
 * headers while only the URL is substituted (ARC-03). Every write carries a
 * reason and a fresh retry key; response text never enters an error or a log.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../api/client';
import { send } from '../../api/transport';
import type { QueueCommand, QueuesView } from './types';

/** Contract path used only as a source of header metadata. */
const HEADER_SOURCE_PATH: ApiPath = '/api/v1/facilities';

/** Build a descriptor for one unpublished queue route. */
function queueDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, HEADER_SOURCE_PATH, meta, body);
  return { ...base, url: path };
}

/** Read the queue counts and the coverage gap for the active tenant. */
export async function listQueues(organizationId: string): Promise<QueuesView> {
  return send<QueuesView>(
    queueDescriptor('GET', '/api/v1/operations/queues', {
      activeOrganization: organizationId,
    }),
  );
}

/**
 * Record one assign, resolve, retry or escalate action with its mandatory
 * reason, guarded by the row version that the operator read.
 */
export async function recordQueueAction(
  command: QueueCommand,
  organizationId: string,
): Promise<QueuesView> {
  const path = `/api/v1/operations/queues/${encodeURIComponent(command.queueId)}/${command.action}`;
  return send<QueuesView>(
    queueDescriptor(
      'POST',
      path,
      {
        activeOrganization: organizationId,
        ifMatch: command.version,
        idempotencyKey: crypto.randomUUID(),
      },
      { reason: command.reason },
    ),
  );
}
