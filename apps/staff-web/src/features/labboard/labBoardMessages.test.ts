import { describe, expect, it } from 'vitest';

import {
  labBoardKeys,
  labBoardMessage,
  labBoardMessages,
  labBoardText,
  numberText,
  stageColumnKey,
} from './labBoardMessages';
import { TURNAROUND_STAGES } from './domain';

/** Named placeholder tokens in a message, sorted for comparison. */
function placeholders(value: string): string[] {
  return [...value.matchAll(/\{(\w+)\}/g)].map((match) => required(match[1])).sort();
}

describe('lab board bilingual bundle', () => {
  it('keeps identical key sets in English and Arabic', () => {
    expect(Object.keys(labBoardMessages.en).sort()).toEqual([...labBoardKeys].sort());
    expect(Object.keys(labBoardMessages.ar).sort()).toEqual([...labBoardKeys].sort());
  });

  it('gives every key a non-empty label in both locales', () => {
    for (const key of labBoardKeys) {
      expect(labBoardMessages.en[key].trim()).not.toBe('');
      expect(labBoardMessages.ar[key].trim()).not.toBe('');
    }
  });

  it('uses the same named placeholders in both locales', () => {
    for (const key of labBoardKeys) {
      expect(placeholders(labBoardMessages.ar[key])).toEqual(
        placeholders(labBoardMessages.en[key]),
      );
    }
  });

  it('translates Arabic labels into Arabic script', () => {
    for (const key of labBoardKeys) {
      expect(
        /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/.test(
          labBoardMessages.ar[key],
        ),
      ).toBe(true);
    }
  });

  it('looks up a label for either locale', () => {
    expect(labBoardText('en', 'title')).toBe('Laboratory turnaround board');
    expect(labBoardText('ar', 'colInAnalysis')).toBe('قيد التحليل');
  });
});

describe('lab board message helpers', () => {
  it('fills named placeholders and leaves unknown ones visible', () => {
    expect(labBoardMessage('en', 'overdue', { minutes: '25' })).toBe('Overdue by 25 min');
    expect(labBoardMessage('en', 'overdue', {})).toBe('Overdue by {minutes} min');
  });

  it('formats numbers with Latin digits in both locales', () => {
    expect(numberText(47, 'en')).toBe('47');
    expect(numberText(47, 'ar')).toBe('47');
  });

  it('maps every stage to its column label key', () => {
    expect(TURNAROUND_STAGES.map(stageColumnKey)).toEqual([
      'colOrdered',
      'colCollected',
      'colInAnalysis',
      'colAwaitingVerification',
      'colReleased',
    ]);
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
