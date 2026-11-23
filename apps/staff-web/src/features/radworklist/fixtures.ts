import { defined } from '../../components/invariant';
import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { ImagingWorklistOrder } from './domain';

/** The tenant whose synthetic radiology orders are shown in tests and previews. */
export const alphaOrganizationId = defined(syntheticOrganizations[0], 'alpha organization').id;

/** The second synthetic tenant; its local order ids deliberately collide. */
export const betaOrganizationId = defined(syntheticOrganizations[1], 'beta organization').id;

/** Synthetic performing sites used by the worklist screen. */
export const syntheticPerformingSite = 'site-rad-main';
export const otherPerformingSite = 'site-rad-other';

/** Synthetic alpha orders across all four worklist statuses. */
export const syntheticOrders: readonly ImagingWorklistOrder[] = [
  {
    organizationId: alphaOrganizationId,
    orderId: 'rad-001',
    performingSiteId: syntheticPerformingSite,
    patientId: 'synthetic-patient-001',
    orderCode: 'ORD-001',
    urgency: 'routine',
    status: 'received',
    version: 1,
    scheduledAt: null,
    modality: null,
    accession: null,
    accessionIssuer: null,
    studyInstanceUid: null,
  },
  {
    organizationId: alphaOrganizationId,
    orderId: 'rad-002',
    performingSiteId: syntheticPerformingSite,
    patientId: 'synthetic-patient-002',
    orderCode: 'ORD-002',
    urgency: 'urgent',
    status: 'scheduled',
    version: 2,
    scheduledAt: '2026-03-09T09:00:00Z',
    modality: 'ct',
    accession: 'ACC-002',
    accessionIssuer: 'SYN-ORG',
    studyInstanceUid: null,
  },
  {
    organizationId: alphaOrganizationId,
    orderId: 'rad-003',
    performingSiteId: syntheticPerformingSite,
    patientId: 'synthetic-patient-003',
    orderCode: 'ORD-003',
    urgency: 'stat',
    status: 'performed',
    version: 3,
    scheduledAt: '2026-03-09T10:00:00Z',
    modality: 'mri',
    accession: 'ACC-003',
    accessionIssuer: 'SYN-ORG',
    studyInstanceUid: '1.2.826.0.1.3680043.2.1.3',
  },
  {
    organizationId: alphaOrganizationId,
    orderId: 'rad-004',
    performingSiteId: syntheticPerformingSite,
    patientId: 'synthetic-patient-004',
    orderCode: 'ORD-004',
    urgency: 'routine',
    status: 'reported',
    version: 4,
    scheduledAt: '2026-03-09T11:00:00Z',
    modality: 'xray',
    accession: 'ACC-004',
    accessionIssuer: 'SYN-ORG',
    studyInstanceUid: '1.2.826.0.1.3680043.2.1.4',
  },
  {
    organizationId: betaOrganizationId,
    orderId: 'rad-001',
    performingSiteId: syntheticPerformingSite,
    patientId: 'synthetic-beta-patient-001',
    orderCode: 'BETA-ORD-001',
    urgency: 'routine',
    status: 'received',
    version: 1,
    scheduledAt: null,
    modality: null,
    accession: null,
    accessionIssuer: null,
    studyInstanceUid: null,
  },
  {
    organizationId: alphaOrganizationId,
    orderId: 'rad-005',
    performingSiteId: otherPerformingSite,
    patientId: 'synthetic-patient-005',
    orderCode: 'ORD-005',
    urgency: 'routine',
    status: 'received',
    version: 1,
    scheduledAt: null,
    modality: null,
    accession: null,
    accessionIssuer: null,
    studyInstanceUid: null,
  },
];
