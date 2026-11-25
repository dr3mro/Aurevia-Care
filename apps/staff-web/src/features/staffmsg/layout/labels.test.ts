import { describe, expect, it } from 'vitest';
import { layoutKeys, layoutMessages, layoutText } from './labels';

describe('staff-message layout labels', () => {
  it('has a distinct key for every label', () => {
    expect(new Set(layoutKeys).size).toBe(layoutKeys.length);
    expect([...layoutKeys].sort()).toEqual(['back', 'composerPane', 'detailPane', 'listPane']);
  });

  it('has a non-empty label for every key in both bundles', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of layoutKeys) {
        expect(layoutMessages[locale][key].length).toBeGreaterThan(0);
      }
    }
  });

  it('translates every key into Arabic letters (UX-01)', () => {
    for (const key of layoutKeys) {
      expect(layoutText('ar', key)).toMatch(/[\u0600-\u06FF]/);
    }
  });

  it('resolves each key from the active bundle', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of layoutKeys) {
        expect(layoutText(locale, key)).toBe(layoutMessages[locale][key]);
      }
    }
  });

  it('pins the literal Back label in both bundles', () => {
    expect(layoutText('en', 'back')).toBe('Back to list');
    expect(layoutText('ar', 'back')).toBe('العودة إلى القائمة');
  });
});
