import { describe, expect, it } from 'vitest';
import {
  criticalStateKey,
  reconciliationReasonKey,
  resultKindKey,
  resultKeys,
  resultMessage,
  resultText,
  reviewStateKey,
} from './messages';

describe('result message bundles', () => {
  it('gives every key a non-empty label in both languages', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of resultKeys) {
        expect(resultText(locale, key).length).toBeGreaterThan(0);
      }
    }
    expect(resultText('ar', 'results')).toMatch(/[\u0600-\u06FF]/);
    expect(resultText('en', 'results')).not.toBe(resultText('ar', 'results'));
  });

  it('fills named placeholders and leaves unknown ones visible', () => {
    expect(resultMessage('en', 'criticalResultsCount', { count: 4 })).toBe(
      '4 critical results awaiting review',
    );
    expect(resultMessage('ar', 'criticalResultsCount', { count: 4 })).toContain('4');
    expect(resultMessage('en', 'criticalResultsCount')).toBe('{count} critical results awaiting review');
  });

  it('maps every wire state to a defined key', () => {
    for (const key of Object.values(reconciliationReasonKey)) {
      expect(resultKeys).toContain(key);
    }
    for (const key of Object.values(resultKindKey)) {
      expect(resultKeys).toContain(key);
    }
    for (const key of Object.values(reviewStateKey)) {
      expect(resultKeys).toContain(key);
    }
    for (const key of Object.values(criticalStateKey)) {
      expect(resultKeys).toContain(key);
    }
  });
});
