/**
 * Real-API client for the unit inbox (MSG-02, MSG-06).
 *
 * Conversation routes are published in the generated contract, but this client
 * builds descriptors through the shared request builder so tenant, correlation,
 * version and retry headers stay owned in one place. Response text never enters
 * an error or a log; failures are normalized by the shared transport.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../api/client';
import { send } from '../../api/transport';
import type { InboxView, SendDraft, WorkDraft } from './types';

/** Published contract path used as the source of header metadata. */
const HEADER_SOURCE_PATH: ApiPath = '/api/v1/conversations/inbox';

/** Build a descriptor for one conversation route. */
function conversationDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, HEADER_SOURCE_PATH, meta, body);
  return { ...base, url: path };
}

/** Read the unit inbox, including unread counts, for the active tenant. */
export async function listInbox(organizationId: string): Promise<InboxView> {
  return send<InboxView>(
    conversationDescriptor('GET', '/api/v1/conversations/inbox', {
      activeOrganization: organizationId,
    }),
  );
}

/**
 * Append a reply that explicitly names its recipient and any files staged for
 * this send. The version precondition rejects a stale thread on 409/412.
 */
export async function sendReply(draft: SendDraft, organizationId: string): Promise<InboxView> {
  const path = `/api/v1/conversations/${encodeURIComponent(draft.threadId)}/reply`;
  return send<InboxView>(
    conversationDescriptor(
      'POST',
      path,
      {
        activeOrganization: organizationId,
        ifMatch: draft.version,
        idempotencyKey: crypto.randomUUID(),
      },
      {
        body: draft.draft,
        recipient: draft.recipientName,
        recipientId: draft.recipientId,
        pendingFiles: [...draft.pendingFiles],
      },
    ),
  );
}

/**
 * Assign or resolve one work item with a recorded reason. Assignment names the
 * assignee; resolution only needs the reason that policy requires.
 */
export async function recordThreadAction(
  command: WorkDraft,
  organizationId: string,
): Promise<InboxView> {
  const path = `/api/v1/conversations/${encodeURIComponent(command.threadId)}/${command.action}`;
  const body: Record<string, unknown> =
    command.action === 'assign'
      ? { assigneeSubject: command.assignee, reason: command.reason }
      : { reason: command.reason };
  return send<InboxView>(
    conversationDescriptor(
      'POST',
      path,
      {
        activeOrganization: organizationId,
        ifMatch: command.version,
        idempotencyKey: crypto.randomUUID(),
      },
      body,
    ),
  );
}
