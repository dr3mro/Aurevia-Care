import { describe, expect, it } from 'vitest';

import {
  differenceGlyph,
  diffRevisions,
  hasDifferences,
  hasReason,
  isWithdrawn,
  releaseVisibility,
  type ObservationDifference,
} from './domain';
import { correctedRevision, identicalRevision, originalRevision } from './fixtures';

/** Find one diff row by its stable test key. */
function diffFor(rows: readonly ObservationDifference[], testId: string): ObservationDifference {
  const row = rows.find((item) => item.testId === testId);
  if (!row) {
    throw new Error(`No difference for ${testId}`);
  }
  return row;
}

describe('LAB-02 correction reason rule', () => {
  it('refuses a blank or whitespace-only reason', () => {
    expect(hasReason('')).toBe(false);
    expect(hasReason('   ')).toBe(false);
  });

  it('accepts a named reason', () => {
    expect(hasReason('Transcription error')).toBe(true);
  });
});

describe('LAB-02 revision comparison', () => {
  it('marks a changed value with both readings and its unit', () => {
    const row = diffFor(diffRevisions(originalRevision, correctedRevision), 'hemoglobin');
    expect(row.kind).toBe('changed');
    expect(row.oldValue).toBe(13.2);
    expect(row.newValue).toBe(7.4);
    expect(row.unit).toBe('g/dL');
  });

  it('marks an unchanged value as unchanged', () => {
    const row = diffFor(diffRevisions(originalRevision, correctedRevision), 'potassium');
    expect(row.kind).toBe('unchanged');
    expect(row.oldValue).toBe(4.2);
    expect(row.newValue).toBe(4.2);
  });

  it('marks a test present only in the original revision as removed', () => {
    const row = diffFor(diffRevisions(originalRevision, correctedRevision), 'sodium');
    expect(row.kind).toBe('removed');
    expect(row.oldValue).toBe(139);
    expect(row.newValue).toBeNull();
  });

  it('marks a test present only in the corrected revision as added', () => {
    const row = diffFor(diffRevisions(originalRevision, correctedRevision), 'vitamin-d');
    expect(row.kind).toBe('added');
    expect(row.oldValue).toBeNull();
    expect(row.newValue).toBe(22);
  });

  it('treats a unit-only change as a change', () => {
    const alternate = {
      ...originalRevision,
      observations: [{ ...required(originalRevision.observations[0]), unit: 'g/L' }],
    };
    const row = diffFor(diffRevisions(originalRevision, alternate), 'hemoglobin');
    expect(row.kind).toBe('changed');
    expect(row.oldValue).toBe(13.2);
    expect(row.newValue).toBe(13.2);
  });

  it('gives every difference kind a distinct shape-distinct glyph', () => {
    expect(new Set(Object.values(differenceGlyph)).size).toBe(4);
  });
});

describe('LAB-02 difference detection', () => {
  it('is false for identical revisions and an empty comparison', () => {
    expect(hasDifferences(diffRevisions(originalRevision, identicalRevision))).toBe(false);
    expect(hasDifferences([])).toBe(false);
  });

  it('is true when at least one observation differs', () => {
    expect(hasDifferences(diffRevisions(originalRevision, correctedRevision))).toBe(true);
  });
});

describe('LAB-02 withdrawal state', () => {
  it('recognises only the withdrawn state', () => {
    expect(isWithdrawn('withdrawn')).toBe(true);
    expect(isWithdrawn('final')).toBe(false);
    expect(isWithdrawn('corrected')).toBe(false);
    expect(isWithdrawn('preliminary')).toBe(false);
  });
});

describe('LAB-02 release status by audience', () => {
  it('withholds a withdrawn report from both audiences', () => {
    expect(releaseVisibility('withdrawn', 'clinician')).toBe('withheld');
    expect(releaseVisibility('withdrawn', 'patient')).toBe('withheld');
  });

  it('withholds a preliminary report from the patient but shows it to the clinician', () => {
    expect(releaseVisibility('preliminary', 'patient')).toBe('withheld');
    expect(releaseVisibility('preliminary', 'clinician')).toBe('released');
  });

  it('releases final and corrected reports to both audiences', () => {
    expect(releaseVisibility('final', 'clinician')).toBe('released');
    expect(releaseVisibility('final', 'patient')).toBe('released');
    expect(releaseVisibility('corrected', 'clinician')).toBe('released');
    expect(releaseVisibility('corrected', 'patient')).toBe('released');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
