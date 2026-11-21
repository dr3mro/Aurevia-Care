import { describe, expect, it } from 'vitest';

import {
  CHANGE_LIMIT_PERCENT,
  canVerify,
  enteredValue,
  flagGlyph,
  hasCriticalResult,
  hasLargeChange,
  relativeChangePercent,
  type ResultRow,
} from './domain';
import {
  blasts,
  emptyReport,
  entererSubject,
  hemoglobin,
  nonCriticalReport,
  sodium,
  syntheticReport,
  verifierSubject,
  vitaminD,
} from './fixtures';

/** A row with a controllable previous value for boundary tests. */
function rowWithValue(value: number, previous: number | null): ResultRow {
  return {
    ...hemoglobin,
    value,
    previous: previous === null ? null : { value: previous, at: '2026-09-14' },
  };
}

describe('LAB-02 delta check', () => {
  it('returns null when the previous value is zero because no percentage exists', () => {
    expect(relativeChangePercent(0.2, 0)).toBeNull();
  });

  it('returns a signed percentage for a non-zero previous value', () => {
    expect(relativeChangePercent(11, 10)).toBe(10);
    expect(relativeChangePercent(5, 10)).toBe(-50);
  });

  it('does not warn without a previous result', () => {
    expect(hasLargeChange(vitaminD)).toBe(false);
  });

  it('does not warn when the previous value was zero', () => {
    expect(hasLargeChange(blasts)).toBe(false);
  });

  it('warns for large changes in both directions', () => {
    expect(hasLargeChange(hemoglobin)).toBe(true);
    expect(hasLargeChange(rowWithValue(20, 10))).toBe(true);
  });

  it('stays quiet within the limit and exactly at the limit', () => {
    expect(hasLargeChange(sodium)).toBe(false);
    expect(hasLargeChange(rowWithValue(12.5, 10))).toBe(false);
    expect(hasLargeChange(rowWithValue(12.6, 10))).toBe(true);
    expect(CHANGE_LIMIT_PERCENT).toBe(25);
  });
});

describe('LAB-02 entered value resolution', () => {
  it('keeps the recorded value for an empty draft', () => {
    expect(enteredValue(hemoglobin, '')).toBe(hemoglobin.value);
    expect(enteredValue(hemoglobin, '   ')).toBe(hemoglobin.value);
  });

  it('keeps the recorded value for a non-numeric draft', () => {
    expect(enteredValue(hemoglobin, 'abc')).toBe(hemoglobin.value);
  });

  it('uses a trimmed, finite numeric draft', () => {
    expect(enteredValue(hemoglobin, '7.6')).toBe(7.6);
    expect(enteredValue(hemoglobin, ' 8.1 ')).toBe(8.1);
  });
});

describe('LAB-02 verification eligibility', () => {
  it('refuses a blank verifier', () => {
    expect(canVerify(entererSubject, '')).toBe(false);
    expect(canVerify(entererSubject, '   ')).toBe(false);
  });

  it('refuses the enterer, even when named', () => {
    expect(canVerify(entererSubject, entererSubject)).toBe(false);
  });

  it('allows a distinct named second user', () => {
    expect(canVerify(entererSubject, verifierSubject)).toBe(true);
  });
});

describe('LAB-02 critical detection', () => {
  it('finds a critical observation on the synthetic report', () => {
    expect(hasCriticalResult(syntheticReport.rows)).toBe(true);
  });

  it('is false when no observation is critical', () => {
    expect(hasCriticalResult(nonCriticalReport.rows)).toBe(false);
    expect(hasCriticalResult(emptyReport.rows)).toBe(false);
  });

  it('gives every flag a distinct word-safe glyph', () => {
    expect(new Set(Object.values(flagGlyph)).size).toBe(4);
  });
});
