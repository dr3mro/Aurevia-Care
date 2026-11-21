/** Synthetic imaging data shared by the radiography screens and their tests. */

import { syntheticOrganizations } from '../../fixtures/synthetic';
import type {
  Addendum,
  ImagingOrder,
  Interpretation,
  StudyLink,
} from './domain';

/** The two synthetic tenants; local identifiers intentionally collide. */
export const alphaOrganizationId = syntheticOrganizations[0]!.id;
export const betaOrganizationId = syntheticOrganizations[1]!.id;

/** A synthetic received order. */
export const receivedOrder: ImagingOrder = {
  id: 'imaging-order-a1',
  version: 1,
  patientId: 'patient-a1',
  patientName: { latinName: 'Layla Haddad', localName: 'ليلى حداد' },
  urgency: 'urgent',
  clinicalQuestion: 'Synthetic: evaluate chest pain',
  state: 'received',
  accessionNo: null,
  modality: null,
  scheduledAt: null,
  studyInstanceUid: null,
  interpretation: null,
};

/** A synthetic Arabic-script order for the RTL and localization tests. */
export const arabicOrder: ImagingOrder = {
  ...receivedOrder,
  id: 'imaging-order-a2',
  patientId: 'patient-a2',
  patientName: { latinName: 'Layla Haddad', localName: 'ليلى حداد' },
  urgency: 'routine',
  clinicalQuestion: 'سؤال سريري توضيحي',
};

/** A scheduled order ready for a study link. */
export const scheduledOrder: ImagingOrder = {
  ...receivedOrder,
  version: 2,
  state: 'scheduled',
  accessionNo: 'ACC-2026-0001',
  modality: 'CT',
  scheduledAt: '2026-03-08T08:00:00Z',
};

/** A study-linked order ready for an interpretation. */
export const studyLinkedOrder: ImagingOrder = {
  ...scheduledOrder,
  version: 3,
  state: 'study-linked',
  studyInstanceUid: '1.2.826.0.1.3680043.2.1125.synthetic',
};

/** A signed interpretation with one linked addendum. */
export const syntheticAddendum: Addendum = {
  id: 'addendum-a1',
  text: 'Synthetic addendum: compared with the prior study.',
  author: 'synthetic.radiologist',
  addedAt: '2026-03-08T10:00:00Z',
};

export const syntheticInterpretation: Interpretation = {
  technique: 'Synthetic CT chest with contrast',
  comparison: 'None available',
  findings: 'No synthetic focal lesion.',
  impression: 'No acute cardiopulmonary abnormality.',
  followUp: 'Clinical correlation advised.',
  signedBy: 'synthetic.radiologist',
  signedAt: '2026-03-08T09:00:00Z',
  addenda: [syntheticAddendum],
};

/** A signed order whose addendum must reach the referrer view. */
export const addendedOrder: ImagingOrder = {
  ...studyLinkedOrder,
  version: 4,
  state: 'addended',
  interpretation: syntheticInterpretation,
};

/** A signed order with no addenda. */
export const interpretedOrder: ImagingOrder = {
  ...studyLinkedOrder,
  version: 4,
  state: 'interpreted',
  interpretation: { ...syntheticInterpretation, addenda: [] },
};

/** The alpha list used by the paging and tenant tests. */
export const syntheticAlphaOrders: readonly ImagingOrder[] = [
  receivedOrder,
  scheduledOrder,
  addendedOrder,
];

/** A beta order that must not leak into the alpha organization. */
export const syntheticBetaOrder: ImagingOrder = {
  ...receivedOrder,
  id: 'imaging-order-b1',
  patientId: 'patient-b1',
  clinicalQuestion: 'Synthetic beta organization order',
};

/** A study whose patient and accession both match the scheduled order. */
export const matchingStudy: StudyLink = {
  studyInstanceUid: studyLinkedOrder.studyInstanceUid!,
  patientId: scheduledOrder.patientId,
  accessionNo: scheduledOrder.accessionNo!,
};

/** A study with the wrong patient but the correct accession. */
export const wrongPatientStudy: StudyLink = {
  ...matchingStudy,
  patientId: 'patient-b1',
};

/** A study with the correct patient but the wrong accession. */
export const wrongAccessionStudy: StudyLink = {
  ...matchingStudy,
  accessionNo: 'ACC-9999',
};
