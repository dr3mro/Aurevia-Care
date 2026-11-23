import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { AddendumAcknowledgment, SignedReport } from './domain';

/** The synthetic tenant whose signed report is shown in tests. */
export const alphaOrganizationId = required(syntheticOrganizations[0]).id;
/** The second synthetic tenant; its local identifiers deliberately collide. */
export const betaOrganizationId = required(syntheticOrganizations[1]).id;
/** Synthetic order the report and its addenda belong to. */
export const syntheticOrderId = 'rad-004';

/** A synthetic signed interpretation with all five narrated sections. */
export const syntheticSignedReport: SignedReport = {
  organizationId: alphaOrganizationId,
  orderId: syntheticOrderId,
  patientId: 'synthetic-patient-004',
  // The current revision after two addenda; matches the open receipt below.
  version: 3,
  signer: 'synthetic.radiologist',
  signedAt: '2026-03-09T12:00:00Z',
  technique: 'PA and lateral chest radiograph at full inspiration.',
  comparison: 'Prior chest radiograph from 2025.',
  findings: 'Clear lung fields. Normal cardiomediastinal silhouette.',
  impression: 'No acute cardiopulmonary abnormality.',
  followUp: 'Repeat imaging in six weeks if symptoms persist.',
};

/**
 * Synthetic receipts for a version-3 report: the current v3 addendum is open,
 * the earlier v2 addendum is acknowledged, and two look-alike receipts belong
 * to another tenant and another order. Only the first is shown.
 */
export const syntheticAddenda: readonly AddendumAcknowledgment[] = [
  {
    organizationId: alphaOrganizationId,
    orderId: syntheticOrderId,
    version: 3,
    state: 'open',
    notifiedAt: '2026-03-09T13:00:00Z',
    acknowledgedAt: null,
    acknowledgedBy: null,
  },
  {
    organizationId: alphaOrganizationId,
    orderId: syntheticOrderId,
    version: 2,
    state: 'acknowledged',
    notifiedAt: '2026-03-09T12:30:00Z',
    acknowledgedAt: '2026-03-09T12:45:00Z',
    acknowledgedBy: 'synthetic.referrer',
  },
  {
    organizationId: betaOrganizationId,
    orderId: syntheticOrderId,
    version: 3,
    state: 'open',
    notifiedAt: '2026-03-09T14:00:00Z',
    acknowledgedAt: null,
    acknowledgedBy: null,
  },
  {
    organizationId: alphaOrganizationId,
    orderId: 'rad-other',
    version: 3,
    state: 'open',
    notifiedAt: '2026-03-09T15:00:00Z',
    acknowledgedAt: null,
    acknowledgedBy: null,
  },
];

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
