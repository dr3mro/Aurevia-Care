import { describe, expect, it } from 'vitest';

import {
  differenceKey,
  formatValue,
  formatWithdrawalTime,
  releaseMessage,
  releaseKeys,
  releaseMessages,
  releaseText,
  stateKey,
  visibilityKey,
} from './labReleaseMessages';
import { WITHDRAWN_AT } from './fixtures';

/** Named placeholder tokens in a message, sorted for comparison. */
function placeholders(value: string): string[] {
  return [...value.matchAll(/\{(\w+)\}/g)].map((match) => required(match[1])).sort();
}

describe('LAB-02 bilingual release bundle', () => {
  it('keeps identical key sets in English and Arabic', () => {
    expect(Object.keys(releaseMessages.en).sort()).toEqual([...releaseKeys].sort());
    expect(Object.keys(releaseMessages.ar).sort()).toEqual([...releaseKeys].sort());
  });

  it('gives every key a non-empty label in both locales', () => {
    for (const key of releaseKeys) {
      expect(releaseMessages.en[key].trim()).not.toBe('');
      expect(releaseMessages.ar[key].trim()).not.toBe('');
    }
  });

  it('uses the same named placeholders in both locales', () => {
    for (const key of releaseKeys) {
      expect(placeholders(releaseMessages.ar[key])).toEqual(placeholders(releaseMessages.en[key]));
    }
  });

  it('translates every Arabic label into Arabic script', () => {
    const arabicLetter =
      /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/;
    for (const key of releaseKeys) {
      expect(arabicLetter.test(releaseMessages.ar[key])).toBe(true);
    }
  });

  it('looks up a label in either locale', () => {
    expect(releaseText('en', 'title')).toBe('Laboratory report release and corrections');
    expect(releaseText('ar', 'stateWithdrawn')).toBe('مُسحوب');
  });

  it('fills placeholders and leaves unknown ones visible', () => {
    expect(
      releaseMessage('en', 'correctedStatus', {
        subject: 'synthetic.lab.supervisor',
        reason: 'typo',
      }),
    ).toBe('Corrected by synthetic.lab.supervisor. Reason: typo');
    expect(releaseMessage('en', 'correctedStatus', {})).toBe(
      'Corrected by {subject}. Reason: {reason}',
    );
    expect(releaseMessage('en', 'title', {})).toBe('Laboratory report release and corrections');
  });
});

describe('LAB-02 release label mappings', () => {
  it('maps every difference kind to a word key', () => {
    expect(differenceKey('changed')).toBe('changeChanged');
    expect(differenceKey('unchanged')).toBe('changeUnchanged');
    expect(differenceKey('added')).toBe('changeAdded');
    expect(differenceKey('removed')).toBe('changeRemoved');
  });

  it('maps every report state to a word key', () => {
    expect(stateKey('preliminary')).toBe('statePreliminary');
    expect(stateKey('final')).toBe('stateFinal');
    expect(stateKey('corrected')).toBe('stateCorrected');
    expect(stateKey('withdrawn')).toBe('stateWithdrawn');
  });

  it('maps both visibility outcomes to a word key', () => {
    expect(visibilityKey('released')).toBe('visibilityReleased');
    expect(visibilityKey('withheld')).toBe('visibilityWithheld');
  });
});

describe('LAB-02 release value formatting', () => {
  it('keeps the value and its invariant unit in one non-breaking-space run', () => {
    expect(formatValue(7.4, 'g/dL', 'en')).toBe('7.4\u00a0g/dL');
    expect(formatValue(7.4, 'g/dL', 'ar')).toBe('7.4\u00a0g/dL');
  });

  it('formats the withdrawal instant in both locales with Latin digits', () => {
    expect(formatWithdrawalTime(WITHDRAWN_AT, 'en')).toContain('2026');
    expect(formatWithdrawalTime(WITHDRAWN_AT, 'ar')).toContain('2026');
    expect(formatWithdrawalTime(WITHDRAWN_AT, 'en')).toContain('12:15');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
