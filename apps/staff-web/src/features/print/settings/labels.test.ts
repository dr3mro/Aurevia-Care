import { describe, expect, it } from 'vitest';
import { settingsKeys, settingsMessage, settingsMessages, settingsText } from './labels';

/** Arabic letters only; Arabic-Indic digits and punctuation do not satisfy UX-01. */
const ARABIC_LETTER =
  /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/;
const PLACEHOLDER = /\{\w+\}/g;

/** Sorted placeholder names, so an Arabic reordering never changes the values. */
function placeholders(value: string): string[] {
  return [...value.matchAll(PLACEHOLDER)].map((match) => match[0].slice(1, -1)).sort();
}

describe('print settings labels', () => {
  it('gives every key a non-empty label in both bundles', () => {
    expect(new Set(settingsKeys).size).toBe(settingsKeys.length);
    for (const locale of ['en', 'ar'] as const) {
      expect(Object.keys(settingsMessages[locale]).sort()).toEqual([...settingsKeys].sort());
      for (const key of settingsKeys) {
        expect(settingsText(locale, key).length).toBeGreaterThan(0);
      }
    }
  });

  it('ships distinct Arabic text with Arabic letters on every Arabic label', () => {
    expect(settingsText('ar', 'title')).toMatch(/[\u0600-\u06FF]/);
    expect(settingsText('en', 'title')).not.toBe(settingsText('ar', 'title'));
    for (const key of settingsKeys) {
      expect(ARABIC_LETTER.test(settingsText('ar', key))).toBe(true);
    }
  });

  it('uses the same named placeholders in both languages', () => {
    for (const key of settingsKeys) {
      expect(placeholders(settingsText('en', key))).toEqual(placeholders(settingsText('ar', key)));
    }
  });

  it('fills named placeholders and leaves unknown ones visible', () => {
    expect(settingsMessage('en', 'proposalSaved', { version: '3' })).toBe(
      'Proposed print profile version 3.',
    );
    expect(settingsMessage('ar', 'approvedNotice', { version: '2' })).toContain('2');
    expect(settingsMessage('en', 'proposalSaved')).toBe(
      'Proposed print profile version {version}.',
    );
  });
});
