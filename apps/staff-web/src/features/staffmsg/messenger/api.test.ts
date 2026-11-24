import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defined } from '../../../components/invariant';
import type { RequestDescriptor } from '../../../api/client';
import { TransportError, send } from '../../../api/transport';
import type * as TransportModule from '../../../api/transport';
import {
  eventStreamDescriptor,
  listConversations,
  listMessages,
  listUnreadCounts,
  markRead,
  narrowConversation,
  narrowEvent,
  narrowEventPage,
  narrowMessage,
  narrowUnread,
  pollEvents,
  sendMessage,
} from './api';
import {
  alphaConversationId,
  alphaOrganizationId,
  facilityId,
  peerSubject,
  selfSubject,
  wireEmptyEventPage,
  wireEventPage,
  wireIncomingMessage,
  wireMessageEvent,
  wireOutgoingMessage,
  wireReadConversation,
  wireReadEvent,
  wireSparseConversation,
  wireSparseEvent,
  wireSparseEventPage,
  wireSparseMessage,
  wireSparseUnread,
  wireUnread,
  wireUnreadConversation,
} from './fixtures';

vi.mock('../../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

/** Read one descriptor by call index in the single-call unit tests. */
function descriptorAt(call: number): RequestDescriptor {
  return defined(sendMock.mock.calls[call], 'send call')[0];
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('instant wire narrowing', () => {
  it('narrows a complete conversation and safe-empty optional fields', () => {
    expect(narrowConversation(wireUnreadConversation)).toMatchObject({
      id: alphaConversationId,
      kind: 'direct',
      topic: 'Synthetic triage peer',
      lastSequence: 12,
      unreadCount: 3,
    });
    expect(narrowConversation(wireReadConversation).topic).toBe('');
    expect(narrowConversation({ id: 'synthetic-conversation-sparse' })).toMatchObject({
      id: 'synthetic-conversation-sparse',
      facilityId: '',
      kind: '',
      topic: '',
      lastSequence: 0,
      unreadCount: 0,
    });
    expect(() => narrowConversation(wireSparseConversation)).toThrow(TransportError);
  });

  it('fails closed when a conversation has no identifier', () => {
    expect(() => narrowConversation({ unreadCount: 1 })).toThrow(TransportError);
  });

  it('narrows incoming, outgoing and sparse messages', () => {
    expect(narrowMessage(wireIncomingMessage, selfSubject)).toMatchObject({
      id: wireIncomingMessage.id,
      body: 'Synthetic incoming body',
      senderSubject: peerSubject,
      outgoing: false,
      delivery: 'delivered',
      lang: 'en',
    });
    expect(narrowMessage(wireOutgoingMessage, selfSubject).outgoing).toBe(true);
    expect(
      narrowMessage(
        { id: 'synthetic-message-sparse', senderSubject: peerSubject, sentAt: '2026-03-09T08:00:00Z' },
        selfSubject,
      ),
    ).toMatchObject({
      id: 'synthetic-message-sparse',
      sequence: 0,
      senderSubject: peerSubject,
      body: '',
      sentAt: '2026-03-09T08:00:00Z',
      outgoing: false,
    });
    expect(() => narrowMessage(wireSparseMessage, selfSubject)).toThrow(TransportError);
  });

  it('fails closed when a message has no identifier', () => {
    expect(() => narrowMessage({ body: 'no id' }, selfSubject)).toThrow(TransportError);
  });

  it('narrows unread counts with a zero default', () => {
    expect(narrowUnread(wireUnread)).toEqual({
      conversationId: alphaConversationId,
      unreadCount: 3,
    });
    expect(narrowUnread({ conversationId: 'synthetic-conversation-sparse' })).toEqual({
      conversationId: 'synthetic-conversation-sparse',
      unreadCount: 0,
    });
    expect(() => narrowUnread(wireSparseUnread)).toThrow(TransportError);
  });

  it('fails closed when an unread count has no conversation id', () => {
    expect(() => narrowUnread({ unreadCount: 4 })).toThrow(TransportError);
  });

  it('narrows identifier events and fails closed on a missing id', () => {
    expect(narrowEvent(wireMessageEvent)).toEqual({
      id: 'synthetic-event-001',
      sequence: 13,
      type: 'message',
      conversationId: alphaConversationId,
      subject: peerSubject,
    });
    expect(narrowEvent(wireReadEvent).type).toBe('read');
    expect(
      narrowEvent({ id: 'synthetic-event-sparse', type: 'message', conversationId: 'synthetic-conversation-sparse' }).subject,
    ).toBe('');
    expect(() => narrowEvent(wireSparseEvent)).toThrow(TransportError);
    expect(() => narrowEvent({ type: 'message' })).toThrow(TransportError);
  });

  it('narrows polling pages including empty and sparse pages', () => {
    expect(narrowEventPage(wireEventPage)).toMatchObject({
      events: [
        { id: 'synthetic-event-001', type: 'message' },
        { id: 'synthetic-event-002', type: 'read' },
      ],
      nextCursor: 14,
    });
    expect(narrowEventPage(wireEmptyEventPage).events).toEqual([]);
    expect(narrowEventPage(wireSparseEventPage)).toEqual({ events: [], nextCursor: undefined });
  });
});

describe('instant reads', () => {
  it('lists conversations with the published URL, tenant header and facility filter', async () => {
    sendMock.mockResolvedValueOnce([wireUnreadConversation, { id: 'synthetic-conversation-sparse' }]);
    const conversations = await listConversations(alphaOrganizationId, facilityId);
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/staff/instant/conversations?facilityId=facility-synthetic-main');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(conversations).toHaveLength(2);
    expect(conversations[0]?.unreadCount).toBe(3);
  });

  it('lists messages with an encoded conversation id and narrows with the self subject', async () => {
    sendMock.mockResolvedValueOnce([wireIncomingMessage, wireOutgoingMessage]);
    const messages = await listMessages(alphaOrganizationId, facilityId, 'thread/1', selfSubject);
    expect(descriptorAt(0).url).toBe(
      '/api/v1/staff/instant/thread%2F1/messages?facilityId=facility-synthetic-main',
    );
    expect(messages.map((entry) => entry.outgoing)).toEqual([false, true]);
  });

  it('lists unread counts with the facility filter', async () => {
    sendMock.mockResolvedValueOnce([wireUnread, { conversationId: 'synthetic-conversation-sparse' }]);
    const counts = await listUnreadCounts(alphaOrganizationId, facilityId);
    expect(descriptorAt(0).url).toBe('/api/v1/staff/instant/unread?facilityId=facility-synthetic-main');
    expect(counts).toEqual([
      { conversationId: alphaConversationId, unreadCount: 3 },
      { conversationId: 'synthetic-conversation-sparse', unreadCount: 0 },
    ]);
  });

  it('polls events with a cursor and limit, then narrows the page', async () => {
    sendMock.mockResolvedValueOnce(wireEventPage);
    const page = await pollEvents(alphaOrganizationId, facilityId, '10', 50);
    expect(descriptorAt(0).url).toBe(
      '/api/v1/staff/instant/events?facilityId=facility-synthetic-main&cursor=10&limit=50',
    );
    expect(page.events).toHaveLength(2);
    expect(page.nextCursor).toBe(14);
  });

  it('polls events without a cursor or limit on the first call', async () => {
    sendMock.mockResolvedValueOnce(wireEmptyEventPage);
    await pollEvents(alphaOrganizationId, facilityId);
    expect(descriptorAt(0).url).toBe('/api/v1/staff/instant/events?facilityId=facility-synthetic-main');
  });

  it('builds a stream descriptor with the tenant header and optional resume id', () => {
    const descriptor = eventStreamDescriptor(alphaOrganizationId, facilityId, '13');
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe(
      '/api/v1/staff/instant/events/stream?facilityId=facility-synthetic-main',
    );
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptor.headers['Last-Event-ID']).toBe('13');
    expect(eventStreamDescriptor(alphaOrganizationId, facilityId).headers['Last-Event-ID']).toBeUndefined();
  });
});

describe('instant commands', () => {
  it('sends exactly the three contract keys with an idempotency key and path binding', async () => {
    sendMock.mockResolvedValueOnce(wireOutgoingMessage);
    const message = await sendMessage(
      alphaOrganizationId,
      { facilityId, conversationId: alphaConversationId, body: 'Synthetic body' },
      'synthetic-key-001',
      selfSubject,
    );
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe(
      '/api/v1/staff/instant/synthetic-conversation-alpha/messages',
    );
    expect(descriptor.headers['Idempotency-Key']).toBe('synthetic-key-001');
    expect(descriptor.body).toEqual({
      facilityId,
      conversationId: alphaConversationId,
      body: 'Synthetic body',
    });
    expect(Object.keys(descriptor.body ?? {})).toHaveLength(3);
    expect(message.outgoing).toBe(true);
  });

  it('reuses the same idempotency key when the same failed send is retried', async () => {
    const command = { facilityId, conversationId: alphaConversationId, body: 'Synthetic body' };
    sendMock.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(wireOutgoingMessage);
    await expect(sendMessage(alphaOrganizationId, command, 'synthetic-key-002', selfSubject)).rejects.toThrow();
    await sendMessage(alphaOrganizationId, command, 'synthetic-key-002', selfSubject);
    expect(descriptorAt(0).headers['Idempotency-Key']).toBe('synthetic-key-002');
    expect(descriptorAt(1).headers['Idempotency-Key']).toBe('synthetic-key-002');
  });

  it('marks a conversation read at an expected sequence', async () => {
    sendMock.mockResolvedValueOnce({ conversationId: alphaConversationId, unreadCount: 0 });
    const unread = await markRead(alphaOrganizationId, {
      facilityId,
      conversationId: alphaConversationId,
      upToSequence: 12,
    });
    expect(descriptorAt(0).url).toBe('/api/v1/staff/instant/read');
    expect(descriptorAt(0).method).toBe('POST');
    expect(descriptorAt(0).body).toEqual({
      facilityId,
      conversationId: alphaConversationId,
      upToSequence: 12,
    });
    expect(unread.unreadCount).toBe(0);
  });
});
