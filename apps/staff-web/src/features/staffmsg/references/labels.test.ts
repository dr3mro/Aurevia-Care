import { describe, expect, it } from 'vitest';
import { referenceKeys, referenceText } from './index';

/** The four fixed codes that may be shown as a subject warning. */
const warningCodes = ['UUID_LIKE', 'DIGIT_RUN', 'EMAIL_LIKE', 'MRN_LIKE'] as const;

describe('reference labels', () => {
  it('has eight distinct keys with a non-empty translation in both locales', () => {
    expect(new Set(referenceKeys).size).toBe(referenceKeys.length);
    expect(referenceKeys.length).toBe(8);
    for (const key of referenceKeys) {
      expect(referenceText('en', key).length).toBeGreaterThan(0);
      expect(referenceText('ar', key).length).toBeGreaterThan(0);
      expect(referenceText('en', key)).not.toBe(referenceText('ar', key));
    }
  });

  it('looks up control words in both locales', () => {
    expect(referenceText('en', 'blocked')).toBe('Blocked');
    expect(referenceText('ar', 'blocked')).toBe('محجوب');
    expect(referenceText('en', 'insert')).toBe('Insert');
    expect(referenceText('ar', 'subjectLabel')).toBe('الموضوع');
  });

  it('resolves each fixed warning code in both locales', () => {
    for (const code of warningCodes) {
      expect(referenceText('en', code).length).toBeGreaterThan(0);
      expect(referenceText('ar', code).length).toBeGreaterThan(0);
      expect(referenceText('en', code)).not.toBe(referenceText('ar', code));
    }
  });
});
