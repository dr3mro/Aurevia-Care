import { describe, expect, it } from 'vitest';

import {
  flagKey,
  formatNumber,
  formatPercent,
  formatPreviousDate,
  formatValue,
  labMessage,
  labResultKeys,
  labResultMessages,
  labText,
  statusKey,
} from './labResultMessages';

/** Named placeholder tokens in a message, sorted for comparison. */
function placeholders(value: string): string[] {
  return [...value.matchAll(/\{(\w+)\}/g)].map((match) => required(match[1])).sort();
}

describe('LAB-02 bilingual result-entry bundle', () => {
  it('keeps identical key sets in English and Arabic', () => {
    expect(Object.keys(labResultMessages.en).sort()).toEqual([...labResultKeys].sort());
    expect(Object.keys(labResultMessages.ar).sort()).toEqual([...labResultKeys].sort());
  });

  it('gives every key a non-empty label in both locales', () => {
    for (const key of labResultKeys) {
      expect(labResultMessages.en[key].trim()).not.toBe('');
      expect(labResultMessages.ar[key].trim()).not.toBe('');
    }
  });

  it('uses the same named placeholders in both locales', () => {
    for (const key of labResultKeys) {
      expect(placeholders(labResultMessages.ar[key])).toEqual(
        placeholders(labResultMessages.en[key]),
      );
    }
  });

  it('translates every Arabic label into Arabic script', () => {
    const arabicLetter =
      /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/;
    for (const key of labResultKeys) {
      expect(arabicLetter.test(labResultMessages.ar[key])).toBe(true);
    }
  });

  it('looks up a label in either locale', () => {
    expect(labText('en', 'flagLow')).toBe('Low');
    expect(labText('ar', 'flagCritical')).toBe('حرج');
  });

  it('fills placeholders and leaves unknown ones visible', () => {
    expect(labMessage('en', 'enteredBy', { subject: 'synthetic.lab.technician' })).toBe(
      'Entered by synthetic.lab.technician',
    );
    expect(labMessage('en', 'enteredBy', {})).toBe('Entered by {subject}');
    expect(labMessage('en', 'title')).toBe('Laboratory result entry and verification');
  });

  it('maps every flag and status to its word key', () => {
    expect(flagKey('normal')).toBe('flagNormal');
    expect(flagKey('low')).toBe('flagLow');
    expect(flagKey('high')).toBe('flagHigh');
    expect(flagKey('critical')).toBe('flagCritical');
    expect(statusKey('preliminary')).toBe('statusPreliminary');
    expect(statusKey('final')).toBe('statusFinal');
  });
});

describe('LAB-02 clinical value formatting', () => {
  it('uses Latin digits for numbers in both locales', () => {
    expect(formatNumber(139, 'en')).toBe('139');
    expect(formatNumber(139, 'ar')).toBe('139');
    expect(formatNumber(1234.5, 'en')).toBe('1,234.5');
  });

  it('keeps value and unit in one non-breaking-space run', () => {
    expect(formatValue(7.4, 'g/dL', 'en')).toBe('7.4\u00a0g/dL');
    expect(formatValue(7.4, 'g/dL', 'ar')).toBe('7.4\u00a0g/dL');
  });

  it('formats the delta percentage with one fractional digit in both locales', () => {
    expect(formatPercent(43.9393, 'en')).toBe('43.9');
    expect(formatPercent(43.9393, 'ar')).toBe('43.9');
  });

  it('formats a previous civil date without shifting the calendar day', () => {
    expect(formatPreviousDate('2026-09-14', 'en')).toBe('September 14, 2026');
    expect(formatPreviousDate('2026-09-14', 'ar')).toContain('2026');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
