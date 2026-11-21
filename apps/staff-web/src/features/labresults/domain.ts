/**
 * Wire shapes and pure rules for laboratory result entry and verification
 * (LAB-02).
 *
 * Flags, reference ranges and verification eligibility are decisions of the
 * source system and site policy. This module only evaluates those decisions and
 * the configured delta limit; it never derives clinical meaning in the client.
 * All data represented here is synthetic.
 */

/** Abnormal flag carried on an observation by the source system (LAB-02). */
export type ResultFlag = 'normal' | 'low' | 'high' | 'critical';

/** Report release status supplied by the source, never derived in the client. */
export type ReportStatus = 'preliminary' | 'final';

/** The previously released value for one test and its civil date. */
export interface PreviousResult {
  /** Previous numeric value; zero is a real value, not an absent one. */
  value: number;
  /** Civil date (YYYY-MM-DD) of the previous result. */
  at: string;
}

/** One numeric observation on a result report. */
export interface ResultRow {
  /** Stable domain key; never an array index or a patient identifier. */
  id: string;
  /** Test name as supplied by the source; isolated when rendered. */
  testName: string;
  /** Value recorded by the source, before any local draft edit. */
  value: number;
  /** Invariant unit symbol; never translated or converted by the client. */
  unit: string;
  /** Site-approved reference range, shown as text from the source. */
  referenceRange: string;
  flag: ResultFlag;
  /** Absent when this test has no previously released result. */
  previous: PreviousResult | null;
  /** Subject of the user who entered this observation. */
  enteredBy: string;
}

/** One report whose observations are entered and verified together. */
export interface ResultReport {
  id: string;
  /** Opaque accession, rendered as an isolated left-to-right run. */
  accession: string;
  status: ReportStatus;
  /** Subject of the user who entered the report. */
  enteredBy: string;
  /** UTC instant at which entry was completed. */
  enteredAt: string;
  rows: readonly ResultRow[];
}

/** Relative change above this percentage raises the configured delta warning. */
export const CHANGE_LIMIT_PERCENT = 25;

/**
 * Percentage change from a previous value, or null when no relative comparison
 * is meaningful. A zero previous value has no defined percentage change.
 */
export function relativeChangePercent(current: number, previous: number): number | null {
  if (previous === 0) {
    return null;
  }
  return ((current - previous) / Math.abs(previous)) * 100;
}

/** Whether an observation changed by more than the configured delta limit. */
export function hasLargeChange(row: ResultRow): boolean {
  if (row.previous === null) {
    return false;
  }
  const change = relativeChangePercent(row.value, row.previous.value);
  if (change === null) {
    return false;
  }
  return Math.abs(change) > CHANGE_LIMIT_PERCENT;
}

/**
 * Resolve the numeric value actually entered. An empty or non-numeric draft
 * keeps the recorded value so a partial edit can never be submitted as a zero.
 */
export function enteredValue(row: ResultRow, draft: string): number {
  const trimmed = draft.trim();
  if (trimmed === '') {
    return row.value;
  }
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : row.value;
}

/** A verifier must be a named user other than the person who entered the report. */
export function canVerify(enteredBy: string, verifier: string): boolean {
  return verifier.trim() !== '' && verifier !== enteredBy;
}

/** Whether any observation on the report requires the critical notification step. */
export function hasCriticalResult(rows: readonly ResultRow[]): boolean {
  return rows.some((row) => row.flag === 'critical');
}

/** Shape-distinct glyphs; every flag is also shown as a word, never colour alone. */
export const flagGlyph: Readonly<Record<ResultFlag, string>> = {
  normal: '✓',
  low: '▼',
  high: '▲',
  critical: '‼',
};

/** Glyph for the configured-limit warning, always paired with a word. */
export const CHANGE_GLYPH = '⚠';
