import { describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { listQueues, recordQueueAction } from './api';
import type { QueuesView } from './types';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

const view: QueuesView = {
  rows: [{ id: 'unsigned-encounters', count: 6, noteKey: 'noteOldest', version: 3 }],
  missingCoverage: {
    unit: 'Imaging desk',
    window: 'Sunday 8:00 to 14:00',
    version: 2,
  },
};

function descriptorAt(call: number): RequestDescriptor {
  const invocation = sendMock.mock.calls[call];
  if (invocation === undefined) {
    throw new Error('missing send call');
  }
  return invocation[0];
}

describe('operations API descriptors', () => {
  it('lists queues for the active tenant', async () => {
    sendMock.mockResolvedValueOnce(view);
    await expect(listQueues('org-alpha')).resolves.toBe(view);
    expect(descriptorAt(0).method).toBe('GET');
    expect(descriptorAt(0).url).toBe('/api/v1/operations/queues');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe('org-alpha');
  });

  it.each(['assign', 'resolve', 'retry', 'escalate'] as const)(
    'records a %s action with tenant, version, retry key and reason',
    async (action) => {
      sendMock.mockResolvedValueOnce(view);
      await recordQueueAction(
        {
          queueId: 'unacknowledged-critical-findings',
          action,
          reason: 'named backup assigned',
          version: 4,
        },
        'org-alpha',
      );
      const descriptor = descriptorAt(0);
      expect(descriptor.method).toBe('POST');
      expect(descriptor.url).toBe(
        `/api/v1/operations/queues/unacknowledged-critical-findings/${action}`,
      );
      expect(descriptor.headers['X-Active-Organization']).toBe('org-alpha');
      expect(descriptor.headers['If-Match']).toBe('4');
      expect(descriptor.headers['Idempotency-Key']).toMatch(/\S/);
      expect(descriptor.body).toEqual({ reason: 'named backup assigned' });
    },
  );
});
