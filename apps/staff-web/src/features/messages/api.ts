/**
 * Real-API client for MSG-02 conversations. Every call carries the active
 * organization through the shared request builder; writes carry the version
 * precondition from the loaded conversation. Response text is never copied
 * into an error or a log.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type { Conversation, ConversationView, Inbox, WorkQueue } from './types';

/**
 * Conversation paths are not yet declared in the generated OpenAPI client, so
 * the shared builder is given a contract path and the concrete conversation
 * URL replaces it. Headers, tenant scope and version metadata stay identical
 * to every other feature client (the pattern already used by `admin/api.ts`).
 */
const CONTRACT_PATH = '/api/v1/facilities';

/** Base path of the conversation resource family. */
const CONVERSATIONS = '/api/v1/conversations';

/** Build a tenant-scoped GET descriptor for a conversation URL. */
function getDescriptor(url: string, organizationId: string): RequestDescriptor {
  return {
    ...buildRequestDescriptor('GET', CONTRACT_PATH, { activeOrganization: organizationId }),
    url,
  };
}

/** Build a versioned POST descriptor with a fresh key for one user action. */
function commandDescriptor(
  url: string,
  organizationId: string,
  version: number,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(
    'POST',
    CONTRACT_PATH,
    {
      activeOrganization: organizationId,
      ifMatch: version,
      idempotencyKey: crypto.randomUUID(),
    },
    body,
  );
  return { ...base, url };
}

/** URL of one conversation command. */
function commandUrl(id: string, command: string): string {
  return `${CONVERSATIONS}/${encodeURIComponent(id)}/${command}`;
}

/** Read the caller's inbox with server-derived unread counts. */
export function getInbox(organizationId: string): Promise<Inbox> {
  return send<Inbox>(getDescriptor(`${CONVERSATIONS}/inbox`, organizationId));
}

/** Read the role-scoped staff work queue. */
export function getWorkQueue(organizationId: string): Promise<WorkQueue> {
  return send<WorkQueue>(getDescriptor(`${CONVERSATIONS}/work-queue`, organizationId));
}

/** Read one conversation with its ordered message and work-item trails. */
export function getConversation(id: string, organizationId: string): Promise<ConversationView> {
  return send<ConversationView>(
    getDescriptor(`${CONVERSATIONS}/${encodeURIComponent(id)}`, organizationId),
  );
}

/** Post a reply; the server returns the updated view with its new version. */
export function reply(
  id: string,
  body: string,
  version: number,
  organizationId: string,
): Promise<ConversationView> {
  return send<ConversationView>(
    commandDescriptor(commandUrl(id, 'reply'), organizationId, version, { body }),
  );
}

/** Assign an open work item to a named staff subject. */
export function assign(
  id: string,
  assigneeSubject: string,
  reason: string,
  version: number,
  organizationId: string,
): Promise<Conversation> {
  return send<Conversation>(
    commandDescriptor(commandUrl(id, 'assign'), organizationId, version, {
      assigneeSubject,
      reason,
    }),
  );
}

/** Transfer a work item to another subject in the same unit. */
export function transfer(
  id: string,
  toSubject: string,
  reason: string,
  version: number,
  organizationId: string,
): Promise<Conversation> {
  return send<Conversation>(
    commandDescriptor(commandUrl(id, 'transfer'), organizationId, version, {
      toSubject,
      reason,
    }),
  );
}

/** Resolve a work item with a reason. */
export function resolve(
  id: string,
  reason: string,
  version: number,
  organizationId: string,
): Promise<Conversation> {
  return send<Conversation>(
    commandDescriptor(commandUrl(id, 'resolve'), organizationId, version, { reason }),
  );
}

/** Close a thread with a reason; the server makes it read-only. */
export function close(
  id: string,
  reason: string,
  version: number,
  organizationId: string,
): Promise<Conversation> {
  return send<Conversation>(
    commandDescriptor(commandUrl(id, 'close'), organizationId, version, { reason }),
  );
}

/** Record that the caller viewed the thread, advancing its read watermark. */
export function read(id: string, version: number, organizationId: string): Promise<Conversation> {
  return send<Conversation>(commandDescriptor(commandUrl(id, 'read'), organizationId, version));
}

/** Normalize an unexpected rejection to the typed error the state views show. */
export function asMessagesTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}
