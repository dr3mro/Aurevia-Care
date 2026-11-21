/**
 * Wire shapes and pure rules for laboratory report release, correction and
 * withdrawal (LAB-02).
 *
 * Report states, observations and release decisions come from the source system
 * and site policy. This module only compares revisions and evaluates the
 * visibility of an already-decided state; it never derives clinical meaning in
 * the client. All data represented here is synthetic.
 */

/** Release state carried from the source system (LAB-02). */
export type ReleaseState = 'preliminary' | 'final' | 'corrected' | 'withdrawn';

/** Who is looking at the released report. */
export type ReleaseAudience = 'clinician' | 'patient';

/** Whether a report is visible to an audience. */
export type ReleaseVisibility = 'released' | 'withheld';

/** How one observation differs between two revisions. */
export type DifferenceKind = 'changed' | 'unchanged' | 'added' | 'removed';

/** One observation value in an immutable report revision. */
export interface ObservationValue {
  /** Stable domain key; never an array index or a patient identifier. */
  testId: string;
  /** Test name as supplied by the source; isolated when rendered. */
  testName: string;
  /** Numeric value recorded by the source. */
  value: number;
  /** Invariant unit symbol; never translated or converted by the client. */
  unit: string;
}

/** One immutable revision of a released report. */
export interface ReportRevision {
  /** Stable revision identifier. */
  id: string;
  /** Opaque accession, rendered as an isolated left-to-right run. */
  accession: string;
  /** Monotonic revision number from the source. */
  version: number;
  state: ReleaseState;
  observations: readonly ObservationValue[];
}

/** One row of a side-by-side comparison of two revisions. */
export interface ObservationDifference {
  testId: string;
  testName: string;
  unit: string;
  /** Absent when the observation exists only in the corrected revision. */
  oldValue: number | null;
  /** Absent when the observation exists only in the original revision. */
  newValue: number | null;
  kind: DifferenceKind;
}

/** The reason and described change captured by a correction or withdrawal. */
export interface CorrectionValues {
  /** Clinical free text describing what changed; stored and shown as written. */
  changeSummary: string;
  /** Mandatory explanation of why the action was taken. */
  reason: string;
}

/** The durable record shown when a report has been withdrawn. */
export interface WithdrawalDetail {
  /** Explanation recorded at withdrawal time. */
  reason: string;
  /** Subject of the user who withdrew the report. */
  actor: string;
  /** UTC instant at which the withdrawal was recorded. */
  recordedAt: string;
}

/** Whether a value carries a non-blank explanation. */
export function hasReason(value: string): boolean {
  return value.trim() !== '';
}

/**
 * Compare two revisions by stable test key, preserving the original order and
 * appending tests that exist only in the corrected revision.
 */
export function diffRevisions(
  before: ReportRevision,
  after: ReportRevision,
): ObservationDifference[] {
  const remaining = new Map(after.observations.map((value) => [value.testId, value]));
  const differences: ObservationDifference[] = [];
  for (const old of before.observations) {
    const current = remaining.get(old.testId);
    if (!current) {
      differences.push({
        testId: old.testId,
        testName: old.testName,
        unit: old.unit,
        oldValue: old.value,
        newValue: null,
        kind: 'removed',
      });
      continue;
    }
    const changed = current.value !== old.value || current.unit !== old.unit;
    differences.push({
      testId: old.testId,
      testName: current.testName,
      unit: current.unit,
      oldValue: old.value,
      newValue: current.value,
      kind: changed ? 'changed' : 'unchanged',
    });
    remaining.delete(old.testId);
  }
  for (const added of remaining.values()) {
    differences.push({
      testId: added.testId,
      testName: added.testName,
      unit: added.unit,
      oldValue: null,
      newValue: added.value,
      kind: 'added',
    });
  }
  return differences;
}

/** Whether any observation differs between two compared revisions. */
export function hasDifferences(differences: readonly ObservationDifference[]): boolean {
  return differences.some((difference) => difference.kind !== 'unchanged');
}

/** Whether a report has been withdrawn and entered in error. */
export function isWithdrawn(state: ReleaseState): boolean {
  return state === 'withdrawn';
}

/**
 * Resolve whether an audience may see a report in the given state.
 *
 * A withdrawn report is withheld from everyone. A preliminary report is a
 * working state shown to clinicians only; patients see released reports.
 * Final and corrected revisions are released to both audiences.
 */
export function releaseVisibility(
  state: ReleaseState,
  audience: ReleaseAudience,
): ReleaseVisibility {
  if (state === 'withdrawn') {
    return 'withheld';
  }
  if (audience === 'patient' && state === 'preliminary') {
    return 'withheld';
  }
  return 'released';
}

/** Shape-distinct glyphs; every kind is also shown as a word, never colour alone. */
export const differenceGlyph: Readonly<Record<DifferenceKind, string>> = {
  changed: '✎',
  unchanged: '=',
  added: '+',
  removed: '−',
};
