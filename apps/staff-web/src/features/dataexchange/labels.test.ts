import { describe, expect, it } from 'vitest';
import { dataExchangeKeys, dataExchangeText } from './labels';

/** Arabic letters only; digits and ISO codes do not satisfy UX-01 on their own. */
const ARABIC_LETTER = /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/;

describe('data exchange bilingual labels (UX-01)', () => {
  it('resolves every key in both locales', () => {
    for (const key of dataExchangeKeys) {
      expect(dataExchangeText('en', key)).toBeTruthy();
      expect(dataExchangeText('ar', key)).toBeTruthy();
    }
    expect(new Set(dataExchangeKeys).size).toBe(dataExchangeKeys.length);
  });

  it('gives every English label an Arabic translation with Arabic letters', () => {
    for (const key of dataExchangeKeys) {
      expect(ARABIC_LETTER.test(dataExchangeText('ar', key))).toBe(true);
    }
  });
});
