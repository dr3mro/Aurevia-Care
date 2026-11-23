import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { CollectionOrder, LabelData, PresentedIdentity } from './domain';

/** The two synthetic tenants; their local identifiers intentionally collide. */
export const alphaOrganizationId = required(syntheticOrganizations[0]).id;
export const betaOrganizationId = required(syntheticOrganizations[1]).id;

/** A synthetic alpha accession with both name forms for the two-name check. */
export const alphaOrder: CollectionOrder = {
  organizationId: alphaOrganizationId,
  id: 'accession-100',
  accessionNo: 'ACC-100',
  accessionIssuer: 'SYN-ISSUER',
  patientId: 'patient-100',
  patientName: { latinName: 'Synthetic Layla', localName: 'ليلى تجريبية' },
  patientMrn: 'MRN-100',
  patientMrnIssuer: 'SYN-ISSUER',
  sourceOrderRef: 'ORDER-100',
  state: 'accessioned',
  version: 1,
};

/** A synthetic beta accession with its own issuer and local-script name. */
export const betaOrder: CollectionOrder = {
  organizationId: betaOrganizationId,
  id: 'accession-200',
  accessionNo: 'ACC-200',
  accessionIssuer: 'SYN-BETA-ISSUER',
  patientId: 'patient-200',
  patientName: { latinName: 'Synthetic Reem', localName: 'ريم تجريبية' },
  patientMrn: 'MRN-200',
  patientMrnIssuer: 'SYN-BETA-ISSUER',
  sourceOrderRef: 'ORDER-200',
  state: 'accessioned',
  version: 1,
};

/** A beta order reusing the alpha accession number to prove tenant isolation. */
export const betaEchoOrder: CollectionOrder = {
  ...betaOrder,
  id: 'accession-200-echo',
  accessionNo: 'ACC-100',
  accessionIssuer: 'SYN-ISSUER',
  patientId: 'patient-100',
};

/** The identity a collector reads for the matching alpha order. */
export const presentedAlpha: PresentedIdentity = {
  patientId: 'patient-100',
  accessionNo: 'ACC-100',
  accessionIssuer: 'SYN-ISSUER',
};

/** Complete synthetic label data for the alpha specimen. */
export const labelAlpha: LabelData = {
  accessionNo: 'ACC-100',
  accessionIssuer: 'SYN-ISSUER',
  patientName: 'Synthetic Layla',
  patientMrn: 'MRN-100',
  patientMrnIssuer: 'SYN-ISSUER',
  specimenType: 'SERUM',
  collectedAt: '2026-03-09T09:00:00Z',
};

/** Synthetic specimen details entered at the collection desk. */
export const specimenDetailsAlpha = {
  specimenType: 'SERUM',
  siteCode: 'MAIN',
  collector: 'synthetic.collector',
  collectedAt: '2026-03-09T09:00:00Z',
  quality: 'ACCEPTABLE',
};

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
