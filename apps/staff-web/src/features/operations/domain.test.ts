import { describe, expect, it } from 'vitest';
import { queueTone, toneForQueue, toneWordKey, hasReason, formatQueueCount } from './domain';
import type { QueueId, QueueRow, QueueTone } from './types';

const row = (id: QueueId, count: number): QueueRow => ({
  id,
  count,
  noteKey: 'noteOldest',
  version: 1,
});

describe('operations queue tone mapping', () => {
  it.each<readonly [QueueId, QueueTone]>([
    ['unsigned-encounters', 'waiting'],
    ['unreconciled-reports', 'waiting'],
    ['unacknowledged-critical-findings', 'urgent'],
    ['rejected-exchanges', 'waiting'],
    ['document-failures', 'calm'],
    ['missing-coverage', 'urgent'],
    ['revoked-accounts', 'calm'],
  ])('maps %s to %s', (id, tone) => {
    expect(toneForQueue(id)).toBe(tone);
  });

  it('keeps a non-zero queue at its configured tone', () => {
    expect(queueTone(row('unacknowledged-critical-findings', 2))).toBe('urgent');
  });

  it('shows an empty queue as calm regardless of its configured tone', () => {
    expect(queueTone(row('unacknowledged-critical-findings', 0))).toBe('calm');
  });

  it.each<readonly [QueueTone, string]>([
    ['urgent', 'toneUrgent'],
    ['waiting', 'toneWaiting'],
    ['calm', 'toneCalm'],
  ])('maps the %s role to its %s word', (tone, key) => {
    expect(toneWordKey(tone)).toBe(key);
  });
});

describe('operations reason and count rules', () => {
  it('rejects a blank or whitespace-only reason', () => {
    expect(hasReason('')).toBe(false);
    expect(hasReason('   \t ')).toBe(false);
  });

  it('accepts a reason with content', () => {
    expect(hasReason('coverage reassigned')).toBe(true);
  });

  it('formats counts with Latin digits in both locales', () => {
    expect(formatQueueCount(6, 'en')).toBe('6');
    expect(formatQueueCount(6, 'ar')).toBe('6');
  });
});
