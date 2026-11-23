import { describe, expect, it } from 'vitest';
import {
  chartPrintMessageKeys,
  chartPrintMessages,
  chartPrintText,
  chartSectionMessageKey,
  leftOutReasonKey,
} from './labels';
import { chartPrintSections } from './domain';

describe('chart print messages', () => {
  it('keeps identical key sets in English and Arabic', () => {
    const expected = [...chartPrintMessageKeys].sort();
    expect(Object.keys(chartPrintMessages.en).sort()).toEqual(expected);
    expect(Object.keys(chartPrintMessages.ar).sort()).toEqual(expected);
  });

  it.each(chartPrintMessageKeys)('resolves %s to a non-empty string in both languages', (key) => {
    expect(chartPrintText('en', key)).toBe(chartPrintMessages.en[key]);
    expect(chartPrintText('ar', key)).toBe(chartPrintMessages.ar[key]);
    expect(chartPrintText('en', key).length).toBeGreaterThan(0);
    expect(chartPrintText('ar', key).length).toBeGreaterThan(0);
  });

  it('maps every section to a distinct translated label', () => {
    const keys = chartPrintSections.map((section) => chartSectionMessageKey[section]);
    expect(new Set(keys).size).toBe(keys.length);
    expect(keys.every((key) => chartPrintText('en', key).length > 0)).toBe(true);
    expect(keys.every((key) => chartPrintText('ar', key).length > 0)).toBe(true);
  });

  it('uses Arabic script in every Arabic value and no placeholders', () => {
    const arabicLetter =
      /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/;
    for (const key of chartPrintMessageKeys) {
      expect(chartPrintMessages.ar[key]).toMatch(arabicLetter);
      expect(chartPrintMessages.en[key]).not.toMatch(/\{\w+\}/);
      expect(chartPrintMessages.ar[key]).not.toMatch(/\{\w+\}/);
    }
  });

  it('maps unsigned and withheld to distinct worded reasons', () => {
    expect(leftOutReasonKey.unsigned).toBe('reasonUnsigned');
    expect(leftOutReasonKey.withheld).toBe('reasonWithheld');
    expect(chartPrintText('en', leftOutReasonKey.unsigned)).toContain('Unsigned');
    expect(chartPrintText('en', leftOutReasonKey.withheld)).toContain('Withheld');
    expect(chartPrintText('ar', leftOutReasonKey.unsigned)).toContain('غير موقّعة');
    expect(chartPrintText('ar', leftOutReasonKey.withheld)).toContain('محجوبة');
  });
});
