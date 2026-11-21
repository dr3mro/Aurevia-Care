import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { actionDescriptor, actionTarget, loadQueues, queueRequest, runQueueAction } from './api';
import {
  deadLetter,
  duplicatePair,
  followUp,
  workItem,
  worklistItem,
} from './fixtures';
import { normalizeQueue } from './domain';
import { queueIds } from './types';
import type { QueueRow } from './types';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const orgId = 'synthetic-org-alpha';
const query = { unitId: 'synthetic-unit', facilityCode: 'MAIN' };

/** A row carrying every field a guarded command can need. */
const row: QueueRow = {
  id: 'row-1',
  primary: 'ORD-SYN-001',
  detail: 'SYN-0001',
  state: 'pending',
  occurredAt: '2026-03-09T11:00:00Z',
  version: 5,
  localMrn: 'MRN-SYN-001',
  mrnIssuer: 'SYNTHETIC',
  survivorId: 'SYN-0002',
  duplicateId: 'SYN-0003',
};

function descriptorAt(index: number): RequestDescriptor {
  return sendMock.mock.calls[index]![0] as RequestDescriptor;
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('queueRequest', () => {
  it('builds tenant-scoped GET descriptors for all five list endpoints', () => {
    const expected: Record<string, string> = {
      'work-items': '/api/v1/exchange/work-items',
      'dead-letters': '/api/v1/delivery/dead-letters',
      'overdue-follow-ups':
        '/api/v1/messaging/instructions/overdue-follow-ups?unitId=synthetic-unit',
      'duplicate-reviews': '/api/v1/duplicate-reviews',
      'clinic-worklist': '/api/v1/clinic/worklist?facilityCode=MAIN',
    };
    for (const queue of queueIds) {
      const descriptor = queueRequest(queue, orgId, query);
      expect(descriptor.method).toBe('GET');
      expect(descriptor.url).toBe(expected[queue]);
      expect(descriptor.headers['X-Active-Organization']).toBe(orgId);
    }
  });
});

describe('actionTarget and actionDescriptor', () => {
  it.each([
    [
      'work-items',
      'review',
      '/api/v1/exchange/work-items/row-1/verify-patient',
      {
        localPatientId: 'SYN-0001',
        localMrn: 'MRN-SYN-001',
        mrnIssuer: 'SYNTHETIC',
        attestation: 'synthetic reason',
      },
    ],
    [
      'dead-letters',
      'retry',
      '/api/v1/delivery/dead-letters/review',
      { eventId: 'row-1', resolution: 'requeued' },
    ],
    [
      'dead-letters',
      'review',
      '/api/v1/delivery/dead-letters/review',
      { eventId: 'row-1', resolution: 'resolved' },
    ],
    [
      'overdue-follow-ups',
      'review',
      '/api/v1/messaging/instructions/follow-up/resolve',
      { instructionId: 'row-1', revision: 5 },
    ],
    [
      'duplicate-reviews',
      'review',
      '/api/v1/duplicate-reviews',
      { survivorId: 'SYN-0002', duplicateId: 'SYN-0003' },
    ],
    [
      'clinic-worklist',
      'assign',
      '/api/v1/clinic/worklist/row-1/assign',
      { assigneeSubject: 'synthetic.operator' },
    ],
    [
      'clinic-worklist',
      'escalate',
      '/api/v1/clinic/worklist/row-1/handoff',
      { toSubject: 'synthetic.supervisor' },
    ],
  ] as const)('maps %s/%s to %s with contract-valid fields', (queue, action, url, body) => {
    expect(actionTarget(queue, action, row, 'synthetic reason')).toMatchObject({ url, body });
    const descriptor = actionDescriptor(queue, action, row, 'synthetic reason', orgId);
    expect(descriptor.method).toBe('POST');
    expect(descriptor.url).toBe(url);
    expect(descriptor.body).toMatchObject({ ...body, reason: 'synthetic reason' });
    expect(descriptor.headers['X-Active-Organization']).toBe(orgId);
    expect(descriptor.headers['If-Match']).toBeUndefined();
  });

  it('never consolidates a duplicate pair without a review ticket and confirmation', () => {
    const descriptor = actionDescriptor('duplicate-reviews', 'review', row, 'r', orgId);
    expect(descriptor.url).toBe('/api/v1/duplicate-reviews');
    expect(descriptor.body).not.toHaveProperty('confirmedIdentifiers');
    expect(descriptor.body).not.toHaveProperty('alternateProcess');
  });
});

describe('loadQueues', () => {
  it('projects all five queues from their server responses', async () => {
    const payloads: Record<string, readonly unknown[]> = {
      'work-items': [workItem()],
      'dead-letters': [deadLetter()],
      'overdue-follow-ups': [followUp()],
      'duplicate-reviews': [duplicatePair()],
      'clinic-worklist': [worklistItem()],
    };
    sendMock.mockImplementation(async (descriptor) => {
      const queue = queueIds.find((candidate) => descriptor.url.includes(queuePath(candidate)));
      return payloads[queue!]!;
    });

    const collection = await loadQueues(orgId, query);
    expect(sendMock).toHaveBeenCalledTimes(5);
    for (const queue of queueIds) {
      expect(collection[queue].status).toBe('ready');
      if (collection[queue].status === 'ready') {
        expect(collection[queue].rows).toEqual(normalizeQueue(queue, payloads[queue]!));
      }
    }
  });

  it('keeps a typed failure local to its queue', async () => {
    const failure = new TransportError('forbidden', {
      correlationId: 'corr-1',
      code: 'denied',
      retryAfterSeconds: undefined,
    });
    sendMock.mockImplementation(async (descriptor) => {
      if (descriptor.url.includes('dead-letters')) {
        throw failure;
      }
      return [];
    });

    const collection = await loadQueues(orgId, query);
    expect(collection['dead-letters']).toEqual({ status: 'error', error: failure });
    expect(collection['work-items']).toEqual({ status: 'ready', rows: [] });
  });

  it('normalises an unexpected rejection to a server error', async () => {
    sendMock.mockImplementation(async (descriptor) => {
      if (descriptor.url.includes('clinic/worklist')) {
        throw new Error('unexpected');
      }
      return [];
    });

    const collection = await loadQueues(orgId, query);
    const load = collection['clinic-worklist'];
    expect(load.status).toBe('error');
    if (load.status === 'error') {
      expect(load.error.kind).toBe('server');
      expect(load.error.correlationId).toBeUndefined();
    }
  });
});

describe('runQueueAction', () => {
  it('sends exactly one command with the supplied reason', async () => {
    sendMock.mockResolvedValueOnce({});
    await runQueueAction('dead-letters', row, 'retry', 'synthetic reason', orgId);
    expect(sendMock).toHaveBeenCalledTimes(1);
    expect(descriptorAt(0).body).toMatchObject({ reason: 'synthetic reason' });
  });
});

/** Stable path fragment used to associate a mocked response with a queue. */
function queuePath(queue: string): string {
  switch (queue) {
    case 'work-items':
      return 'exchange/work-items';
    case 'dead-letters':
      return 'delivery/dead-letters';
    case 'overdue-follow-ups':
      return 'overdue-follow-ups';
    case 'duplicate-reviews':
      return 'duplicate-reviews';
    default:
      return 'clinic/worklist';
  }
}
