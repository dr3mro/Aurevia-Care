import { describe, expect, it } from 'vitest';

import { resultsKeys, resultsMessage, resultsMessages, resultsText } from './resultsMessages';

describe('results message bundles', () => {
  it('has the same key set in English and Arabic', () => {
    const english = Object.keys(resultsMessages.en).sort();
    const arabic = Object.keys(resultsMessages.ar).sort();
    expect(english).toEqual(arabic);
    expect(english).toEqual([...resultsKeys].sort());
  });

  it('gives every key a non-empty translation in both locales', () => {
    for (const key of resultsKeys) {
      expect(resultsText('en', key).trim().length).toBeGreaterThan(0);
      expect(resultsText('ar', key).trim().length).toBeGreaterThan(0);
    }
  });

  it('fills a named placeholder without reordering the sentence', () => {
    expect(resultsMessage('en', 'criticalCount', { count: '3' })).toBe('3 need action');
    expect(resultsMessage('ar', 'criticalCount', { count: '3' })).toContain('3');
  });

  it('leaves an unfilled placeholder visible rather than rendering a blank', () => {
    expect(resultsMessage('en', 'criticalCount', {})).toBe('{count} need action');
    expect(resultsMessage('en', 'criticalCount')).toBe('{count} need action');
  });

  it('resolves a plain label for the active locale', () => {
    expect(resultsText('en', 'title')).toBe('Results');
    expect(resultsText('ar', 'title')).toBe('النتائج');
  });
});
