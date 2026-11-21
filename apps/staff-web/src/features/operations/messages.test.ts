import { describe, expect, it } from 'vitest';
import { actionKey, displayQueueText, operationsKeys, queuesText, queueTabKey } from './messages';
import { queueIds } from './types';

describe('operations messages', () => {
  it('provides a non-empty label in both bundles for every key', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of operationsKeys) {
        expect(queuesText(locale, key).length).toBeGreaterThan(0);
      }
    }
    expect(queuesText('en', 'queuesTitle')).not.toBe(queuesText('ar', 'queuesTitle'));
    expect(queuesText('ar', 'queuesTitle')).toMatch(/[\u0600-\u06FF]/);
  });

  it('translates feature-owned state words and leaves server text verbatim', () => {
    expect(displayQueueText('ar', 'pending review')).toBe('بانتظار المراجعة');
    expect(displayQueueText('ar', 'No reason recorded')).toBe('لم يُسجَّل سبب');
    expect(displayQueueText('ar', 'overdue')).toBe('overdue');
    expect(displayQueueText('en', 'assigned')).toBe('assigned');
  });

  it('maps every queue and action to a declared message key', () => {
    for (const queue of queueIds) {
      expect(operationsKeys).toContain(queueTabKey[queue]);
    }
    for (const action of ['assign', 'retry', 'escalate', 'review'] as const) {
      expect(operationsKeys).toContain(actionKey[action]);
    }
  });
});
