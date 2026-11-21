import type { ResultReport, ResultRow } from './domain';

/**
 * Synthetic laboratory report for result entry and verification. No patient,
 * accession, analyser or clinician is real; identifiers and subjects are opaque.
 */

/** Subject of the synthetic user who entered the report. */
export const entererSubject = 'synthetic.lab.technician';

/** Subject of the independent synthetic user who may verify it. */
export const verifierSubject = 'synthetic.lab.verifier';

/** Fixed previous civil date so rendered strings are deterministic. */
const PREVIOUS_DATE = '2026-09-14';

/** A low haemoglobin with a large negative delta from the previous result. */
export const hemoglobin: ResultRow = {
  id: 'hemoglobin',
  testName: 'Hemoglobin',
  value: 7.4,
  unit: 'g/dL',
  referenceRange: '12.0–16.0',
  flag: 'low',
  previous: { value: 13.2, at: PREVIOUS_DATE },
  enteredBy: entererSubject,
};

/** A critical potassium with a large positive delta; requires the notification step. */
export const potassium: ResultRow = {
  id: 'potassium',
  testName: 'Potassium',
  value: 6.8,
  unit: 'mmol/L',
  referenceRange: '3.5–5.1',
  flag: 'critical',
  previous: { value: 4.2, at: PREVIOUS_DATE },
  enteredBy: entererSubject,
};

/** A normal sodium with only a small delta; no warning is expected. */
export const sodium: ResultRow = {
  id: 'sodium',
  testName: 'Sodium',
  value: 139,
  unit: 'mmol/L',
  referenceRange: '135–145',
  flag: 'normal',
  previous: { value: 138, at: PREVIOUS_DATE },
  enteredBy: entererSubject,
};

/** A high blasts count whose previous value was zero, so no percentage exists. */
export const blasts: ResultRow = {
  id: 'blasts',
  testName: 'Blasts',
  value: 0.2,
  unit: '×10⁹/L',
  referenceRange: '0.0–0.1',
  flag: 'high',
  previous: { value: 0, at: PREVIOUS_DATE },
  enteredBy: entererSubject,
};

/** A first-time result with no previous value to compare. */
export const vitaminD: ResultRow = {
  id: 'vitamin-d',
  testName: 'Vitamin D',
  value: 22,
  unit: 'ng/mL',
  referenceRange: '20–50',
  flag: 'normal',
  previous: null,
  enteredBy: entererSubject,
};

/** The complete synthetic report, including one critical observation. */
export const syntheticReport: ResultReport = {
  id: 'report-synthetic-01',
  accession: 'LAB-1042',
  status: 'preliminary',
  enteredBy: entererSubject,
  enteredAt: '2026-11-21T09:40:00Z',
  rows: [hemoglobin, potassium, sodium, blasts, vitaminD],
};

/** A report without any critical observation, used to prove the notification step is conditional. */
export const nonCriticalReport: ResultReport = {
  ...syntheticReport,
  id: 'report-synthetic-02',
  accession: 'LAB-1043',
  rows: [hemoglobin, sodium, vitaminD],
};

/** An empty report, used to exercise the explicit empty state. */
export const emptyReport: ResultReport = {
  ...syntheticReport,
  id: 'report-synthetic-empty',
  accession: 'LAB-0000',
  rows: [],
};
