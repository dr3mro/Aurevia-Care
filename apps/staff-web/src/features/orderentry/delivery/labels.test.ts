import { describe, expect, it } from 'vitest';
import { deliveryKeys, deliveryMessages, deliveryText } from './labels';

describe('delivery choice bilingual bundle', () => {
  it('keeps identical key sets in English and Arabic', () => {
    const expected = [...deliveryKeys].sort();
    expect(Object.keys(deliveryMessages.en).sort()).toEqual(expected);
    expect(Object.keys(deliveryMessages.ar).sort()).toEqual(expected);
  });

  it.each(deliveryKeys)('resolves %s to a non-empty label in both languages', (key) => {
    expect(deliveryText('en', key)).toBe(deliveryMessages.en[key]);
    expect(deliveryText('ar', key)).toBe(deliveryMessages.ar[key]);
    expect(deliveryText('en', key).trim()).not.toBe('');
    expect(deliveryText('ar', key).trim()).not.toBe('');
  });

  it('translates every Arabic label into Arabic script', () => {
    for (const key of deliveryKeys) {
      expect(
        /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/.test(
          deliveryMessages.ar[key],
        ),
      ).toBe(true);
    }
  });
});
