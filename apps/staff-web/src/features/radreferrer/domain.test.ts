import { describe, expect, it } from 'vitest';
import { canAcknowledge, openAddenda, REPORT_SECTION_KEYS, reportSections } from './domain';
import type { AddendumAcknowledgment, SignedReport } from './domain';
import {
  alphaOrganizationId,
  syntheticAddenda,
  syntheticOrderId,
  syntheticSignedReport,
} from './fixtures';

/** The open alpha addendum used by the single-receipt checks. */
const openAddendum = required(syntheticAddenda[0]);
/** The acknowledged alpha addendum used by the negative checks. */
const acknowledgedAddendum = required(syntheticAddenda[1]);
/** The open beta addendum used by the cross-tenant check. */
const otherTenantAddendum = required(syntheticAddenda[2]);
/** The open addendum addressed to another order. */
const otherOrderAddendum = required(syntheticAddenda[3]);

describe('reportSections', () => {
  it('returns the five signed sections in canonical clinical order', () => {
    // Source property order is deliberately scrambled: ordering must come from
    // the vocabulary, not from the object's key order.
    const report: SignedReport = {
      followUp: 'Repeat imaging in six weeks.',
      impression: 'No acute cardiopulmonary abnormality.',
      findings: 'Clear lung fields. Normal cardiomediastinal silhouette.',
      comparison: 'Prior chest radiograph from 2025.',
      technique: 'PA and lateral chest radiograph.',
      organizationId: alphaOrganizationId,
      orderId: syntheticOrderId,
      patientId: 'synthetic-patient-004',
      version: 1,
      signer: 'synthetic.radiologist',
      signedAt: '2026-03-09T12:00:00Z',
    };

    const sections = reportSections(report);
    expect(sections.map((section) => section.key)).toEqual([...REPORT_SECTION_KEYS]);
    expect(sections.map((section) => section.body)).toEqual([
      report.technique,
      report.comparison,
      report.findings,
      report.impression,
      report.followUp,
    ]);
    // Synthetic fixture stays internally consistent: the current report
    // revision is the one the open addendum receipt refers to.
    expect(syntheticSignedReport.version).toBe(openAddendum.version);
  });
});

describe('canAcknowledge', () => {
  it('allows only an addendum that is still open', () => {
    expect(canAcknowledge(openAddendum)).toBe(true);
    expect(canAcknowledge(acknowledgedAddendum)).toBe(false);
  });
});

describe('openAddenda', () => {
  it('keeps only open addenda for the active tenant and order', () => {
    const open = openAddenda(syntheticAddenda, alphaOrganizationId, syntheticOrderId);
    expect(open.map((addendum) => addendum.version)).toEqual([openAddendum.version]);
  });

  it('excludes an addendum belonging to another tenant', () => {
    const addenda: AddendumAcknowledgment[] = [otherTenantAddendum];
    expect(openAddenda(addenda, alphaOrganizationId, syntheticOrderId)).toEqual([]);
  });

  it('excludes an addendum addressed to another order', () => {
    const addenda: AddendumAcknowledgment[] = [otherOrderAddendum];
    expect(openAddenda(addenda, alphaOrganizationId, syntheticOrderId)).toEqual([]);
  });

  it('excludes an addendum that has already been acknowledged', () => {
    const addenda: AddendumAcknowledgment[] = [acknowledgedAddendum];
    expect(openAddenda(addenda, alphaOrganizationId, syntheticOrderId)).toEqual([]);
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
