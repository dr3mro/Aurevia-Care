/**
 * Real-API client for the staff instant-messenger panel (MSG-02, MSG-03,
 * WEB-09).
 *
 * Every route below is already published in the generated contract, so this
 * module never edits that contract. Descriptors are built with the shared
 * header builder and sent through the shared JSON transport, keeping tenant
 * scope, correlation and idempotency in one place. Query strings are applied
 * by overriding `descriptor.url` after the published base path is built,
 * because `ApiPath` names paths without query strings. Wire responses have all
 * fields optional; they are narrowed once here so an absent id, sequence or
 * count never reaches the panel, and response text is never copied into an
 * error or a log.
 */

import { buildRequestDescriptor } from '../../../api/client';
import type { ApiPath, RequestDescriptor } from '../../../api/client';
import { TransportError, send } from '../../../api/transport';
import { MESSENGER_VIEW_TIME_ZONE } from './types';
import type {
  ConversationView,
  MessageView,
  RealtimeNotification,
  RealtimePage,
  WireConversation,
  WireEvent,
  WireEventPage,
  WireMessage,
  WireReadRequest,
  WireSendRequest,
  WireUnread,
} from './types';

/** Base paths whose query strings are added by overriding the descriptor URL. */
const CONVERSATIONS_PATH: ApiPath = '/api/v1/staff/instant/conversations';
const MESSAGES_PATH: ApiPath = '/api/v1/staff/instant/{conversationId}/messages';
const UNREAD_PATH: ApiPath = '/api/v1/staff/instant/unread';
const EVENTS_PATH: ApiPath = '/api/v1/staff/instant/events';
const STREAM_PATH: ApiPath = '/api/v1/staff/instant/events/stream';
const READ_PATH: ApiPath = '/api/v1/staff/instant/read';

/** Options that vary between otherwise identical published requests. */
interface DescriptorOptions {
  readonly idempotencyKey?: string | undefined;
  readonly body?: Record<string, unknown> | undefined;
}

/** Build a descriptor from a published path and optional request options. */
function descriptor(
  method: 'GET' | 'POST',
  path: ApiPath,
  organizationId: string,
  options: DescriptorOptions = {},
): RequestDescriptor {
  const meta = {
    activeOrganization: organizationId,
    ...(options.idempotencyKey === undefined ? {} : { idempotencyKey: options.idempotencyKey }),
  };
  return buildRequestDescriptor(method, path, meta, options.body);
}

/** Fail closed on a malformed response with a typed, body-free failure. */
function requireField<T>(value: T | undefined, field: string): T {
  if (value === undefined) {
    throw new TransportError('invalid', {
      correlationId: undefined,
      code: field,
      retryAfterSeconds: undefined,
    });
  }
  return value;
}

/** Treat an absent wire string or null as empty text. */
function orText(value: string | null | undefined): string {
  return value ?? '';
}

/** Treat an absent wire number as zero. */
function orZero(value: number | undefined): number {
  return value ?? 0;
}

/** Treat an absent wire collection as empty. */
function orEmpty<T>(value: readonly T[] | undefined): readonly T[] {
  return value ?? [];
}

/** Narrow one wire conversation into the required-field view type. */
export function narrowConversation(wire: WireConversation): ConversationView {
  return {
    id: requireField(wire.id, 'conversation id'),
    facilityId: orText(wire.facilityId),
    kind: orText(wire.kind),
    topic: orText(wire.topic),
    lastSequence: orZero(wire.lastSequence),
    unreadCount: orZero(wire.unreadCount),
  };
}

/**
 * Narrow one wire message. The peer's subject decides whether the message is
 * outgoing; published messages are always delivered because only the composer
 * tracks sending and failure locally.
 */
export function narrowMessage(wire: WireMessage, selfSubject: string): MessageView {
  const senderSubject = requireField(wire.senderSubject, 'sender subject');
  return {
    id: requireField(wire.id, 'message id'),
    sequence: orZero(wire.sequence),
    senderSubject,
    body: orText(wire.body),
    sentAt: requireField(wire.sentAt, 'sent at'),
    timeZone: MESSENGER_VIEW_TIME_ZONE,
    lang: 'en',
    outgoing: senderSubject === selfSubject,
    delivery: 'delivered',
  };
}

/** Narrow one wire unread count. */
export function narrowUnread(wire: WireUnread): { conversationId: string; unreadCount: number } {
  return {
    conversationId: requireField(wire.conversationId, 'unread conversation id'),
    unreadCount: orZero(wire.unreadCount),
  };
}

/** Narrow one identifier-only wire event. */
export function narrowEvent(wire: WireEvent): RealtimeNotification {
  return {
    id: requireField(wire.id, 'event id'),
    sequence: orZero(wire.sequence),
    type: requireField(wire.type, 'event type'),
    conversationId: requireField(wire.conversationId, 'event conversation id'),
    subject: orText(wire.subject),
  };
}

/** Narrow one polling page, including an invisible page whose cursor advances. */
export function narrowEventPage(wire: WireEventPage): RealtimePage {
  return {
    events: orEmpty(wire.events).map(narrowEvent),
    nextCursor: wire.nextCursor,
  };
}

/** Read the active facility's conversations in the active organization. */
export async function listConversations(
  organizationId: string,
  facilityId: string,
): Promise<ConversationView[]> {
  const base = descriptor('GET', CONVERSATIONS_PATH, organizationId);
  const url = `${base.url}?facilityId=${encodeURIComponent(facilityId)}`;
  const wire = await send<WireConversation[]>({ ...base, url });
  return wire.map(narrowConversation);
}

/** Read one conversation's message trail, ordered by server sequence. */
export async function listMessages(
  organizationId: string,
  facilityId: string,
  conversationId: string,
  selfSubject: string,
): Promise<MessageView[]> {
  const base = descriptor('GET', MESSAGES_PATH, organizationId);
  const url = `${base.url.replace('{conversationId}', encodeURIComponent(conversationId))}?facilityId=${encodeURIComponent(facilityId)}`;
  const wire = await send<WireMessage[]>({ ...base, url });
  return wire.map((entry) => narrowMessage(entry, selfSubject));
}

/** Read per-conversation unread counts for the active facility. */
export async function listUnreadCounts(
  organizationId: string,
  facilityId: string,
): Promise<{ conversationId: string; unreadCount: number }[]> {
  const base = descriptor('GET', UNREAD_PATH, organizationId);
  const url = `${base.url}?facilityId=${encodeURIComponent(facilityId)}`;
  const wire = await send<WireUnread[]>({ ...base, url });
  return wire.map(narrowUnread);
}

/** Poll one bounded page of identifier-only events with an exclusive cursor. */
export async function pollEvents(
  organizationId: string,
  facilityId: string,
  cursor?: string,
  limit?: number,
): Promise<RealtimePage> {
  const base = descriptor('GET', EVENTS_PATH, organizationId);
  const params = new URLSearchParams({ facilityId });
  if (cursor !== undefined) {
    params.set('cursor', cursor);
  }
  if (limit !== undefined) {
    params.set('limit', String(limit));
  }
  const wire = await send<WireEventPage>({ ...base, url: `${base.url}?${params.toString()}` });
  return narrowEventPage(wire);
}

/** Build the SSE request descriptor with tenant headers and a resume id. */
export function eventStreamDescriptor(
  organizationId: string,
  facilityId: string,
  lastEventId?: string,
): RequestDescriptor {
  const base = descriptor('GET', STREAM_PATH, organizationId);
  const headers = { ...base.headers };
  if (lastEventId !== undefined) {
    headers['Last-Event-ID'] = lastEventId;
  }
  return {
    ...base,
    url: `${base.url}?facilityId=${encodeURIComponent(facilityId)}`,
    headers,
  };
}

/**
 * Publish one message. The wire body is exactly the published request shape:
 * staged file names are UI state and never enter it. The path conversation id
 * and the body conversation id must match, and the idempotency key makes a
 * retried send produce one message.
 */
export async function sendMessage(
  organizationId: string,
  command: WireSendRequest,
  idempotencyKey: string,
  selfSubject: string,
): Promise<MessageView> {
  const base = descriptor('POST', MESSAGES_PATH, organizationId, {
    idempotencyKey,
    body: {
      facilityId: command.facilityId,
      conversationId: command.conversationId,
      body: command.body,
    },
  });
  const url = base.url.replace('{conversationId}', encodeURIComponent(command.conversationId));
  const wire = await send<WireMessage>({ ...base, url });
  return narrowMessage(wire, selfSubject);
}

/** Advance the caller's read cursor in one conversation. */
export async function markRead(
  organizationId: string,
  request: WireReadRequest,
): Promise<{ conversationId: string; unreadCount: number }> {
  const base = descriptor('POST', READ_PATH, organizationId, {
    body: {
      facilityId: request.facilityId,
      conversationId: request.conversationId,
      upToSequence: request.upToSequence,
    },
  });
  const wire = await send<WireUnread>(base);
  return narrowUnread(wire);
}
