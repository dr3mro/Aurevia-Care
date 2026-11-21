import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  assign,
  asMessagesTransportError,
  close,
  getConversation,
  getInbox,
  getWorkQueue,
  read,
  reply,
  resolve,
  transfer,
} from './api';
import { alphaOrganizationId, conversationId } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptor(): RequestDescriptor {
  return sendMock.mock.calls[0]![0] as RequestDescriptor;
}

beforeEach(() => {
  sendMock.mockReset();
  sendMock.mockResolvedValue({});
});

describe('messages reads', () => {
  it('reads the inbox with only the active organization header', async () => {
    await getInbox(alphaOrganizationId);
    expect(descriptor()).toEqual({
      url: '/api/v1/conversations/inbox',
      method: 'GET',
      headers: { 'X-Active-Organization': alphaOrganizationId },
    });
  });

  it('reads the role-scoped work queue', async () => {
    await getWorkQueue(alphaOrganizationId);
    expect(descriptor().url).toBe('/api/v1/conversations/work-queue');
  });

  it('encodes an opaque conversation id into the detail URL', async () => {
    await getConversation('conv/with space', alphaOrganizationId);
    expect(descriptor().url).toBe('/api/v1/conversations/conv%2Fwith%20space');
  });
});

describe('messages commands', () => {
  it('posts a reply with a fresh key, version and body', async () => {
    await reply(conversationId, 'Synthetic reply', 4, alphaOrganizationId);
    const sent = descriptor();
    expect(sent.url).toBe(`/api/v1/conversations/${conversationId}/reply`);
    expect(sent.method).toBe('POST');
    expect(sent.body).toEqual({ body: 'Synthetic reply' });
    expect(sent.headers['Content-Type']).toBe('application/json');
    expect(sent.headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(sent.headers['If-Match']).toBe('4');
    expect(sent.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('posts an assignment with subject and reason', async () => {
    await assign(conversationId, 'staff-2', 'Synthetic handover', 2, alphaOrganizationId);
    expect(descriptor().url).toBe(`/api/v1/conversations/${conversationId}/assign`);
    expect(descriptor().body).toEqual({ assigneeSubject: 'staff-2', reason: 'Synthetic handover' });
    expect(descriptor().headers['If-Match']).toBe('2');
  });

  it('posts a transfer with target subject and reason', async () => {
    await transfer(conversationId, 'staff-3', 'Synthetic coverage', 3, alphaOrganizationId);
    expect(descriptor().url).toBe(`/api/v1/conversations/${conversationId}/transfer`);
    expect(descriptor().body).toEqual({ toSubject: 'staff-3', reason: 'Synthetic coverage' });
  });

  it('posts a resolution with a reason', async () => {
    await resolve(conversationId, 'Synthetic resolved', 5, alphaOrganizationId);
    expect(descriptor().url).toBe(`/api/v1/conversations/${conversationId}/resolve`);
    expect(descriptor().body).toEqual({ reason: 'Synthetic resolved' });
  });

  it('posts a close with a reason', async () => {
    await close(conversationId, 'Synthetic closed', 6, alphaOrganizationId);
    expect(descriptor().url).toBe(`/api/v1/conversations/${conversationId}/close`);
    expect(descriptor().body).toEqual({ reason: 'Synthetic closed' });
  });

  it('posts a read with the version and no body', async () => {
    await read(conversationId, 7, alphaOrganizationId);
    const sent = descriptor();
    expect(sent.url).toBe(`/api/v1/conversations/${conversationId}/read`);
    expect(sent.body).toBeUndefined();
    expect(sent.headers['Content-Type']).toBeUndefined();
    expect(sent.headers['If-Match']).toBe('7');
  });

  it('uses a new idempotency key for each user action', async () => {
    await reply(conversationId, 'one', 1, alphaOrganizationId);
    await reply(conversationId, 'two', 1, alphaOrganizationId);
    const first = sendMock.mock.calls[0]![0] as RequestDescriptor;
    const second = sendMock.mock.calls[1]![0] as RequestDescriptor;
    expect(first.headers['Idempotency-Key']).not.toBe(second.headers['Idempotency-Key']);
  });
});

describe('asMessagesTransportError', () => {
  it('returns an existing transport error unchanged', () => {
    const failure = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'STALE',
      retryAfterSeconds: undefined,
    });
    expect(asMessagesTransportError(failure)).toBe(failure);
  });

  it('normalizes an unexpected rejection to a server error', () => {
    const normalized = asMessagesTransportError(new Error('unexpected'));
    expect(normalized.kind).toBe('server');
    expect(normalized.correlationId).toBeUndefined();
  });
});
