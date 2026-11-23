import type { ReportRevision, WithdrawalDetail } from './domain';

/**
 * Synthetic laboratory release fixtures. No patient, accession, specimen,
 * clinician or organisation here is real; identifiers and subjects are opaque.
 */

/** Subject of the synthetic supervisor who records the correction. */
export const correctorSubject = 'synthetic.lab.supervisor';

/** Subject of the synthetic manager who withdraws a released report. */
export const withdrawerSubject = 'synthetic.lab.manager';

/** Fixed instant so the withdrawal banner renders deterministically. */
export const WITHDRAWN_AT = '2026-11-21T12:15:00Z';

/** The proposed 'what changed' text, isolated as clinical free text when shown. */
export const proposedChange = 'Hemoglobin 13.2 to 7.4 g/dL; Sodium removed; Vitamin D added';

/** Original released revision: one changed value, an unchanged one and a removed test. */
export const originalRevision: ReportRevision = {
  id: 'release-original',
  accession: 'LAB-1042',
  version: 1,
  state: 'final',
  observations: [
    { testId: 'hemoglobin', testName: 'Hemoglobin', value: 13.2, unit: 'g/dL' },
    { testId: 'potassium', testName: 'Potassium', value: 4.2, unit: 'mmol/L' },
    { testId: 'sodium', testName: 'Sodium', value: 139, unit: 'mmol/L' },
  ],
};

/** Corrected revision: Hemoglobin changed, Potassium unchanged, Sodium removed, Vitamin D added. */
export const correctedRevision: ReportRevision = {
  id: 'release-corrected',
  accession: 'LAB-1042',
  version: 2,
  state: 'corrected',
  observations: [
    { testId: 'hemoglobin', testName: 'Hemoglobin', value: 7.4, unit: 'g/dL' },
    { testId: 'potassium', testName: 'Potassium', value: 4.2, unit: 'mmol/L' },
    { testId: 'vitamin-d', testName: 'Vitamin D', value: 22, unit: 'ng/mL' },
  ],
};

/** A revision with no differences from itself, used to prove the empty comparison state. */
export const identicalRevision: ReportRevision = {
  ...originalRevision,
  id: 'release-identical',
  version: 3,
};

/** A revision with no observations, used to prove the page's explicit empty state. */
export const emptyRevision: ReportRevision = {
  ...correctedRevision,
  id: 'release-empty',
  observations: [],
};

/** The recorded withdrawal of the corrected revision. */
export const withdrawalDetail: WithdrawalDetail = {
  reason: 'Released against the wrong specimen accession.',
  actor: withdrawerSubject,
  recordedAt: WITHDRAWN_AT,
};
