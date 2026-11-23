import { describe, expect, it } from 'vitest';
import { inboxKeys, inboxMessages, inboxText } from './labels';

const ARABIC_LETTER =
  /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u0665-\u06FA-\u06FC\u06FF]/;

describe('unit inbox bilingual labels', () => {
  it('has identical non-empty English and Arabic key sets', () => {
    expect(Object.keys(inboxMessages.en).sort()).toEqual([...inboxKeys].sort());
    expect(Object.keys(inboxMessages.ar).sort()).toEqual([...inboxKeys].sort());
    for (const key of inboxKeys) {
      expect(inboxMessages.en[key].trim().length).toBeGreaterThan(0);
      expect(inboxMessages.ar[key].trim().length).toBeGreaterThan(0);
      expect(ARABIC_LETTER.test(inboxMessages.ar[key])).toBe(true);
    }
  });

  it('resolves a label through the locale lookup', () => {
    expect(inboxText('en', 'title')).toBe('Unit inbox');
    expect(inboxText('ar', 'title')).toBe(inboxMessages.ar.title);
  });
});
