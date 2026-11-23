import { describe, expect, it } from 'vitest';
import { operationsKeys, operationsMessages, operationsText } from './labels';

/** Arabic letters; digits and punctuation do not make a value translated. */
const ARABIC_LETTER =
  /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u0665-\u06D5\u06FA-\u06FC\u06FF]/;

describe('operations bilingual labels', () => {
  it('has identical non-empty English and Arabic key sets', () => {
    expect(Object.keys(operationsMessages.en).sort()).toEqual([...operationsKeys].sort());
    expect(Object.keys(operationsMessages.ar).sort()).toEqual([...operationsKeys].sort());
    for (const key of operationsKeys) {
      expect(operationsMessages.en[key].trim().length).toBeGreaterThan(0);
      expect(operationsMessages.ar[key].trim().length).toBeGreaterThan(0);
      expect(ARABIC_LETTER.test(operationsMessages.ar[key])).toBe(true);
    }
  });

  it('resolves a label through the locale lookup', () => {
    expect(operationsText('en', 'title')).toBe('Operations queues');
    expect(operationsText('ar', 'title')).toBe(operationsMessages.ar.title);
  });
});
