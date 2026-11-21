import { describe, expect, it } from 'vitest';
import { TransportError } from '../../api/transport';
import {
  actionsFor,
  ageMs,
  formatAge,
  normalizeQueue,
  oldestOccurrence,
  queueCounts,
} from './domain';
import {
  deadLetter,
  duplicatePair,
  followUp,
  workItem,
  worklistItem,
} from './fixtures';
import type { QueueCollection, QueueRow } from './types';
import { queueIds } from './types';

function row(overrides: Partial<QueueRow> = {}): QueueRow {
  return {
    id: 'row-1',
    primary: 'SYN-0001',
    detail: 'detail',
    state: 'pending',
    occurredAt: '2026-03-09T11:00:00Z',
    version: undefined,
    localMrn: undefined,
    mrnIssuer: undefined,
    survivorId: undefined,
    duplicateId: undefined,
    ...overrides,
  };
}

describe('actionsFor', () => {
  it('declares the real API actions for every queue', () => {
    expect(actionsFor('work-items')).toEqual(['review']);
    expect(actionsFor('dead-letters')).toEqual(['retry', 'review']);
    expect(actionsFor('overdue-follow-ups')).toEqual(['review']);
    expect(actionsFor('duplicate-reviews')).toEqual(['review']);
    expect(actionsFor('clinic-worklist')).toEqual(['assign', 'escalate']);
  });
});

describe('normalizeQueue', () => {
  it('projects work items from server fields', () => {
    expect(normalizeQueue('work-items', [workItem()])).toEqual([
      {
        id: 'work-001',
        primary: 'ORD-SYN-001',
        detail: 'SYN-0001',
        state: 'awaiting_local_patient',
        occurredAt: '2026-03-09T11:30:00Z',
        version: undefined,
        localMrn: 'MRN-SYN-001',
        mrnIssuer: 'SYNTHETIC',
        survivorId: undefined,
        duplicateId: undefined,
      },
    ]);
  });

  it('projects dead letters with both review states', () => {
    expect(normalizeQueue('dead-letters', [deadLetter()])[0]!.state).toBe('pending review');
    expect(
      normalizeQueue('dead-letters', [
        deadLetter({ eventId: 'evt-2', reviewer: 'synthetic.reviewer' }),
      ])[0]!.state,
    ).toBe('reviewed');
  });

  it('projects follow-ups with and without a recorded reason', () => {
    expect(normalizeQueue('overdue-follow-ups', [followUp()])[0]!.detail).toBe(
      'no acknowledgment',
    );
    expect(
      normalizeQueue('overdue-follow-ups', [followUp({ instructionId: 'i-2', reason: null })])[0]!
        .detail,
    ).toBe('No reason recorded');
  });

  it('projects duplicate pairs keyed by both identifiers with no timestamp', () => {
    expect(normalizeQueue('duplicate-reviews', [duplicatePair()])).toEqual([
      {
        id: 'SYN-0002:SYN-0003',
        primary: 'SYN-0002',
        detail: 'SYN-0003',
        state: 'pending review',
        occurredAt: undefined,
        version: undefined,
        localMrn: undefined,
        mrnIssuer: undefined,
        survivorId: 'SYN-0002',
        duplicateId: 'SYN-0003',
      },
    ]);
  });

  it('projects clinic worklist rows with both assignment states', () => {
    expect(normalizeQueue('clinic-worklist', [worklistItem()])[0]!.state).toBe('unassigned');
    expect(
      normalizeQueue('clinic-worklist', [
        worklistItem({ id: 'wl-2', assigneeSubject: 'synthetic.nurse' }),
      ])[0]!.state,
    ).toBe('assigned');
  });

  it('keeps the server revision for guarded follow-up commands', () => {
    expect(normalizeQueue('overdue-follow-ups', [followUp()])[0]!.version).toBe(2);
    expect(normalizeQueue('clinic-worklist', [worklistItem()])[0]!.version).toBe(1);
    expect(normalizeQueue('dead-letters', [deadLetter()])[0]!.version).toBeUndefined();
  });
});

describe('oldestOccurrence and ageMs', () => {
  it('returns undefined when no row carries a server timestamp', () => {
    expect(oldestOccurrence([])).toBeUndefined();
    expect(oldestOccurrence([row({ occurredAt: undefined })])).toBeUndefined();
  });

  it('ignores rows without timestamps and returns the earliest instant', () => {
    const oldest = oldestOccurrence([
      row({ id: 'a', occurredAt: '2026-03-09T11:00:00Z' }),
      row({ id: 'b', occurredAt: undefined }),
      row({ id: 'c', occurredAt: '2026-03-09T09:00:00Z' }),
      row({ id: 'd', occurredAt: '2026-03-09T12:30:00Z' }),
    ]);
    expect(oldest).toBe('2026-03-09T09:00:00Z');
  });

  it('compares instants numerically so fractional seconds cannot invert the order', () => {
    const oldest = oldestOccurrence([
      row({ id: 'a', occurredAt: '2026-03-09T09:00:00Z' }),
      row({ id: 'b', occurredAt: '2026-03-09T09:00:00.500Z' }),
    ]);
    expect(oldest).toBe('2026-03-09T09:00:00Z');
  });

  it('computes a non-negative age from an injected clock', () => {
    const now = Date.parse('2026-03-09T12:00:00Z');
    expect(ageMs('2026-03-09T11:30:00Z', now)).toBe(30 * 60_000);
    expect(ageMs('2026-03-09T13:00:00Z', now)).toBe(0);
  });
});

describe('formatAge', () => {
  it('renders minutes, hours and days with the runtime locale rules', () => {
    expect(formatAge(5 * 60_000, 'en')).toMatch(/minute/);
    expect(formatAge(3 * 60 * 60_000, 'en')).toMatch(/hour/);
    expect(formatAge(48 * 60 * 60_000, 'en')).toMatch(/day/);
    expect(formatAge(5 * 60_000, 'ar')).toBeTruthy();
  });
});

describe('queueCounts', () => {
  it('counts ready rows and treats a failed queue as empty', () => {
    const failure = new TransportError('server', {
      correlationId: undefined,
      code: undefined,
      retryAfterSeconds: undefined,
    });
    const collection: QueueCollection = {
      'work-items': { status: 'ready', rows: [row(), row({ id: 'r2' })] },
      'dead-letters': { status: 'error', error: failure },
      'overdue-follow-ups': { status: 'ready', rows: [row()] },
      'duplicate-reviews': { status: 'ready', rows: [] },
      'clinic-worklist': { status: 'ready', rows: [row()] },
    };
    expect(queueCounts(collection)).toEqual({
      'work-items': 2,
      'dead-letters': 0,
      'overdue-follow-ups': 1,
      'duplicate-reviews': 0,
      'clinic-worklist': 1,
    });
    expect(Object.keys(queueCounts(collection))).toEqual([...queueIds]);
  });
});
