/**
 * Synthetic pharmacy fixtures. No real person, prescription or identifier is
 * represented here; every value is prefixed or scoped for safe review.
 */

/** First synthetic tenant used by the pharmacy screens. */
export const alphaOrganizationId = 'org-alpha';

/** Second synthetic tenant used to prove a switch clears visible rows. */
export const betaOrganizationId = 'org-beta';

/** One prescription explicitly routed to the synthetic pharmacy. */
export const syntheticInboxItem = {
  id: 'rx-synthetic-001',
  version: 3,
  patientId: 'SYN-PATIENT-001',
  medication: 'Synthetic Amoxicillin',
  strength: '10 mg',
  dose: '500 mg',
  route: 'oral',
  frequency: 'twice daily',
  quantity: 20,
  state: 'delivery_confirmed',
  submissionId: 'submission-synthetic-001',
  prescriberSubject: 'synthetic.prescriber',
  destinationPharmacy: 'synthetic-pharmacy',
  createdAt: '2026-11-21T09:00:00Z',
} as const;

/** One delivery-confirmed fact with a partner detail. */
export const syntheticDeliveryEvent = {
  id: 'event-synthetic-delivery',
  organizationId: alphaOrganizationId,
  prescriptionId: syntheticInboxItem.id,
  version: 2,
  type: 'delivery_confirmed',
  actorId: 'synthetic.partner',
  occurredAt: '2026-11-21T09:30:00Z',
  detail: 'Synthetic partner',
  dispensedBy: null,
  dispensedAt: null,
  handedOver: null,
} as const;

/** One submitted fact without a detail, covering the absent-detail branch. */
export const syntheticSubmittedEvent = {
  id: 'event-synthetic-submitted',
  organizationId: alphaOrganizationId,
  prescriptionId: syntheticInboxItem.id,
  version: 1,
  type: 'submitted',
  actorId: 'synthetic.prescriber',
  occurredAt: '2026-11-21T09:10:00Z',
  detail: null,
  dispensedBy: null,
  dispensedAt: null,
  handedOver: null,
} as const;

/** Current status of a prescription the pharmacy has not responded to yet. */
export const syntheticStatus = {
  organizationId: alphaOrganizationId,
  prescriptionId: syntheticInboxItem.id,
  version: 3,
  accepted: false,
  dispensed: false,
  events: [syntheticDeliveryEvent, syntheticSubmittedEvent],
} as const;

/** Status after dispensing evidence, which never implies acceptance. */
export const syntheticDispensedStatus = {
  organizationId: alphaOrganizationId,
  prescriptionId: syntheticInboxItem.id,
  version: 4,
  accepted: false,
  dispensed: true,
  events: [
    {
      id: 'event-synthetic-dispense',
      organizationId: alphaOrganizationId,
      prescriptionId: syntheticInboxItem.id,
      version: 4,
      type: 'dispensed',
      actorId: 'synthetic.pharmacist',
      occurredAt: '2026-11-21T10:30:00Z',
      detail: null,
      dispensedBy: 'synthetic.pharmacist',
      dispensedAt: '2026-11-21T10:30:00Z',
      handedOver: '30 tablets',
    },
  ],
} as const;

/** One acceptance fact. */
export const syntheticAcceptedEvent = {
  id: 'event-synthetic-accept',
  organizationId: alphaOrganizationId,
  prescriptionId: syntheticInboxItem.id,
  version: 4,
  type: 'accepted',
  actorId: 'synthetic.pharmacist',
  occurredAt: '2026-11-21T10:00:00Z',
  detail: null,
  dispensedBy: null,
  dispensedAt: null,
  handedOver: null,
} as const;

/** Status after acceptance, which never implies dispensing evidence. */
export const syntheticAcceptedStatus = {
  organizationId: alphaOrganizationId,
  prescriptionId: syntheticInboxItem.id,
  version: 4,
  accepted: true,
  dispensed: false,
  events: [syntheticAcceptedEvent],
} as const;

/** A status view with no facts at all. */
export const statusWithoutEvents = {
  organizationId: alphaOrganizationId,
  prescriptionId: syntheticInboxItem.id,
  version: 1,
  accepted: false,
  dispensed: false,
  events: [],
} as const;

/** Synthetic dispensing evidence entered on its own form. */
export const syntheticDispensingEvidence = {
  dispensedBy: 'synthetic.pharmacist',
  dispensedAt: '2026-11-21T10:30:00Z',
  handedOver: '30 tablets',
} as const;
