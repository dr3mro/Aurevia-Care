import { describe, expect, it } from 'vitest';
import {
  orderPrintMessageKeys,
  orderPrintMessages,
  orderPrintText,
  priorityMessageKey,
} from './labels';

/** Arabic letters only, matching the global message-parity registry's rule. */
const arabicLetter =
  /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/;

describe('order requisition messages', () => {
  it('keeps identical key sets in English and Arabic', () => {
    const expected = [...orderPrintMessageKeys].sort();
    expect(Object.keys(orderPrintMessages.en).sort()).toEqual(expected);
    expect(Object.keys(orderPrintMessages.ar).sort()).toEqual(expected);
  });

  it.each(orderPrintMessageKeys)('resolves %s to a non-empty string in both languages', (key) => {
    expect(orderPrintText('en', key)).toBe(orderPrintMessages.en[key]);
    expect(orderPrintText('ar', key)).toBe(orderPrintMessages.ar[key]);
    expect(orderPrintText('en', key).length).toBeGreaterThan(0);
    expect(orderPrintText('ar', key).length).toBeGreaterThan(0);
    expect(arabicLetter.test(orderPrintText('ar', key))).toBe(true);
  });

  it('maps each priority to a distinct translated label', () => {
    const keys = Object.values(priorityMessageKey);
    expect(new Set(keys).size).toBe(keys.length);
    expect(keys.every((key) => orderPrintText('en', key).length > 0)).toBe(true);
    expect(keys.every((key) => orderPrintText('ar', key).length > 0)).toBe(true);
  });
});
