import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { listInbox, recordThreadAction, sendReply } from './api';
import { alphaInboxView } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function descriptorAt(call: number): RequestDescriptor {
  const invocation = sendMock.mock.calls[call];
  if (invocation === undefined) {
    throw new Error('missing send call');
  }
  return invocation[0];
}

describe('unit inbox API descriptors', () => {
  it('lists the tenant inbox at the published path', async () => {
    sendMock.mockResolvedValueOnce(alphaInboxView);
    await expect(listInbox('org-alpha')).resolves.toBe(alphaInboxView);
    expect(descriptorAt(0).method).toBe('GET');
    expect(descriptorAt(0).url).toBe('/api/v1/conversations/inbox');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe('org-alpha');
  });

  it('sends a reply with concrete id, version, retry key, recipient and pending files', async () => {
    sendMock.mockResolvedValueOnce(alphaInboxView);
    await sendReply(
      {
        threadId: 'thread-001',
        version: 3,
        recipientName: 'Synthetic Grace Okafor',
        recipientId: 'synthetic-patient-001',
        draft: 'Thank you',
        pendingFiles: ['prep-instructions.pdf'],
      },
      'org-alpha',
    );
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe('/api/v1/conversations/thread-001/reply');
    expect(descriptor.headers['X-Active-Organization']).toBe('org-alpha');
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/\S/);
    expect(descriptor.body).toEqual({
      body: 'Thank you',
      recipient: 'Synthetic Grace Okafor',
      recipientId: 'synthetic-patient-001',
      pendingFiles: ['prep-instructions.pdf'],
    });
  });

  it.each([
    ['assign', '/api/v1/conversations/thread-001/assign', { assigneeSubject: 'synthetic.backup', reason: 'handover' }],
    ['resolve', '/api/v1/conversations/thread-001/resolve', { reason: 'answered' }],
  ] as const)('records a %s action with a reason and precondition', async (action, url, body) => {
    sendMock.mockResolvedValueOnce(alphaInboxView);
    await recordThreadAction(
      {
        threadId: 'thread-001',
        version: 3,
        action,
        reason: action === 'assign' ? 'handover' : 'answered',
        assignee: 'synthetic.backup',
      },
      'org-alpha',
    );
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe(url);
    expect(descriptor.headers['X-Active-Organization']).toBe('org-alpha');
    expect(descriptor.headers['If-Match']).toBe('3');
    expect(descriptor.headers['Idempotency-Key']).toMatch(/\S/);
    expect(descriptor.body).toEqual(body);
  });

  it('encodes a thread id with reserved characters in the command path', async () => {
    sendMock.mockResolvedValueOnce(alphaInboxView);
    await recordThreadAction(
      { threadId: 'thread/1', version: 1, action: 'resolve', reason: 'closed', assignee: '' },
      'org-alpha',
    );
    expect(descriptorAt(0).url).toBe('/api/v1/conversations/thread%2F1/resolve');
  });
});
