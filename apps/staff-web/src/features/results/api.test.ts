import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import {
  acknowledgeCritical,
  acknowledgeResult,
  asResultTransportError,
  assignCritical,
  escalateCritical,
  getCriticalReport,
  getCriticalResult,
  getResult,
  listReconciliations,
  readBackCritical,
} from './api';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const ORGANIZATION = '11111111-1111-1111-1111-111111111111';

/** Read the descriptor handed to the mocked transport at one call index. */
function descriptorAt(index: number): RequestDescriptor {
  return sendMock.mock.calls[index]![0] as RequestDescriptor;
}

beforeEach(() => {
  sendMock.mockReset();
  sendMock.mockResolvedValue({});
});

describe('result API descriptors', () => {
  it('lists reconciliation entries with the requested status', async () => {
    await listReconciliations('open', ORGANIZATION);
    expect(descriptorAt(0)).toMatchObject({
      method: 'GET',
      url: '/api/v1/xch04/reconciliations?status=open',
      headers: { 'X-Active-Organization': ORGANIZATION },
    });
  });

  it('reads one result revision by an encoded identifier', async () => {
    await getResult('report/one', ORGANIZATION);
    expect(descriptorAt(0).url).toBe('/api/v1/xch04/results/report%2Fone');
    expect(descriptorAt(0).method).toBe('GET');
  });

  it('acknowledges an exact result revision with If-Match and no body', async () => {
    await acknowledgeResult('report-1', 3, ORGANIZATION);
    expect(descriptorAt(0)).toMatchObject({
      method: 'POST',
      url: '/api/v1/xch04/results/report-1/acknowledge',
      headers: { 'If-Match': '3' },
    });
    expect(descriptorAt(0).body).toBeUndefined();
  });

  it('reads the critical operational report', async () => {
    await getCriticalReport(ORGANIZATION);
    expect(descriptorAt(0).url).toBe('/api/v1/critical-results/report');
  });

  it('reads one critical result and its event trail', async () => {
    await getCriticalResult('critical/one', ORGANIZATION);
    expect(descriptorAt(0).url).toBe('/api/v1/critical-results/critical%2Fone');
  });

  it('acknowledges a critical result with a note and version', async () => {
    await acknowledgeCritical('critical-1', 5, 'Synthetic note', ORGANIZATION);
    expect(descriptorAt(0)).toMatchObject({
      method: 'POST',
      url: '/api/v1/critical-results/critical-1/acknowledge',
      headers: { 'If-Match': '5', 'Content-Type': 'application/json' },
      body: { note: 'Synthetic note' },
    });
  });

  it('assigns an owner and a due instant', async () => {
    await assignCritical('critical-1', 2, 'clinician.a', '2026-03-08T13:00:00Z', ORGANIZATION);
    expect(descriptorAt(0)).toMatchObject({
      url: '/api/v1/critical-results/critical-1/assign',
      body: { ownerSubject: 'clinician.a', dueAt: '2026-03-08T13:00:00Z' },
    });
    expect(descriptorAt(0).headers['If-Match']).toBe('2');
  });

  it('sends the reason as the escalation detail with an approved channel', async () => {
    await escalateCritical(
      'critical-1',
      4,
      'Synthetic backup reason',
      'BACKUP_CHANNEL',
      'clinician.b',
      'delivered',
      false,
      ORGANIZATION,
    );
    expect(descriptorAt(0)).toMatchObject({
      url: '/api/v1/critical-results/critical-1/escalate',
      headers: { 'If-Match': '4' },
      body: {
        channel: 'BACKUP_CHANNEL',
        recipient: 'clinician.b',
        outcome: 'delivered',
        detail: 'Synthetic backup reason',
        systemDown: false,
      },
    });
  });

  it('carries a failed outcome and a system-down flag verbatim', async () => {
    await escalateCritical(
      'critical-1',
      4,
      'Synthetic failure reason',
      'BACKUP_CHANNEL',
      'clinician.b',
      'failed',
      true,
      ORGANIZATION,
    );
    expect(descriptorAt(0).body).toMatchObject({ outcome: 'failed', systemDown: true });
  });

  it('records a verbatim read-back', async () => {
    await readBackCritical('critical-1', 6, '2.1 mg/L', ORGANIZATION);
    expect(descriptorAt(0)).toMatchObject({
      url: '/api/v1/critical-results/critical-1/read-back',
      body: { repeatedValue: '2.1 mg/L' },
    });
    expect(descriptorAt(0).headers['If-Match']).toBe('6');
  });
});

describe('asResultTransportError', () => {
  it('preserves a typed transport error', () => {
    const error = new TransportError('conflict', {
      correlationId: 'corr-1',
      code: 'STALE_VERSION',
      retryAfterSeconds: undefined,
    });
    expect(asResultTransportError(error)).toBe(error);
  });

  it('normalizes an unexpected rejection to a server error', () => {
    const normalized = asResultTransportError('unexpected');
    expect(normalized.kind).toBe('server');
    expect(normalized.correlationId).toBeUndefined();
  });
});
