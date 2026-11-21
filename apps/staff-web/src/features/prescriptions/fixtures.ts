import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { PrescriptionResponse } from './api';
import type {
  DraftFields,
  DrugCandidate,
  PrescriptionEvent,
  VerifiedAllergy,
} from './domain';

/** The two synthetic tenants; their local identifiers intentionally collide. */
export const alphaOrganizationId = syntheticOrganizations[0]!.id;
export const betaOrganizationId = syntheticOrganizations[1]!.id;

/** Stable synthetic submission id used across every synthetic event. */
export const syntheticSubmissionId = 'submission-synthetic-001';

/** Verified allergies attached to the synthetic patient chart. */
export const syntheticAllergies: readonly VerifiedAllergy[] = [
  { ingredient: 'penicillin', reaction: 'rash' },
  { ingredient: 'sulfa', reaction: 'hives' },
];

/** Synthetic market-approved catalog plus one unapproved research entry. */
export const syntheticCatalog: readonly DrugCandidate[] = [
  {
    code: 'DRUG-AMOX',
    version: 2,
    genericName: 'Amoxicillin',
    tradeName: 'Amoxil',
    formulation: 'tablet',
    strength: '500 mg',
    ingredients: ['penicillin'],
    marketApproved: true,
    matchTags: ['generic', 'first-line'],
  },
  {
    code: 'DRUG-AMOXCLAV',
    version: 1,
    genericName: 'Amoxicillin clavulanate',
    tradeName: 'Augmentin',
    formulation: 'tablet',
    strength: '875 mg',
    ingredients: ['penicillin', 'clavulanate'],
    marketApproved: true,
    matchTags: ['combination'],
  },
  {
    code: 'DRUG-CEF',
    version: 3,
    genericName: 'Cefalexin',
    tradeName: 'Keflex',
    formulation: 'capsule',
    strength: '250 mg',
    ingredients: ['cefalexin'],
    marketApproved: true,
    matchTags: ['alternative'],
  },
  {
    code: 'DRUG-RESEARCH',
    version: 1,
    genericName: 'Amoxicillin investigational',
    tradeName: 'Research only',
    formulation: 'tablet',
    strength: '500 mg',
    ingredients: ['penicillin'],
    marketApproved: false,
    matchTags: ['research'],
  },
];

/** The market-approved drug the synthetic draft opens with. */
export const syntheticSelectedDrug: DrugCandidate = syntheticCatalog[0]!;

/** Build a complete synthetic draft that individual tests can narrow. */
export function createSyntheticDraft(overrides: Partial<DraftFields> = {}): DraftFields {
  return {
    patientId: 'synthetic-patient-001',
    prescriber: 'synthetic.prescriber',
    drugCode: 'DRUG-AMOX',
    drugVersion: 2,
    strength: '500 mg',
    formulation: 'tablet',
    dose: '1 tablet',
    route: 'oral',
    frequency: 'three times daily',
    quantity: '21',
    refills: '2',
    instructions: 'Take with water after meals',
    destination: 'synthetic-pharmacy',
    channel: 'e-prescribing',
    attested: true,
    overrideReason: '',
    ...overrides,
  };
}

/** The version the prescriber opened, used by the compare action. */
export const syntheticOpenedDraft = createSyntheticDraft();

/** Build one synthetic API prescription response with an event timeline. */
export function createSyntheticPrescription(
  overrides: Partial<PrescriptionResponse> = {},
): PrescriptionResponse {
  return {
    id: 'rx-synthetic-001',
    version: 2,
    submissionId: syntheticSubmissionId,
    transmission: 'confirmed',
    events: [
      {
        kind: 'submitted',
        occurredAt: '2026-03-09T09:00:00Z',
        submissionId: syntheticSubmissionId,
      },
      {
        kind: 'delivery_confirmed',
        occurredAt: '2026-03-09T09:01:00Z',
        submissionId: syntheticSubmissionId,
      },
      {
        kind: 'accepted',
        occurredAt: '2026-03-09T09:05:00Z',
        submissionId: syntheticSubmissionId,
      },
    ],
    ...overrides,
  };
}

/** One event of every kind, used to prove the timeline labels are complete. */
export const syntheticEvents: readonly PrescriptionEvent[] = [
  { kind: 'submitted', occurredAt: '2026-03-09T09:00:00Z', submissionId: syntheticSubmissionId },
  {
    kind: 'delivery_confirmed',
    occurredAt: '2026-03-09T09:01:00Z',
    submissionId: syntheticSubmissionId,
  },
  { kind: 'accepted', occurredAt: '2026-03-09T09:05:00Z', submissionId: syntheticSubmissionId },
  { kind: 'rejected', occurredAt: '2026-03-09T09:06:00Z', submissionId: syntheticSubmissionId },
  { kind: 'dispensed', occurredAt: '2026-03-09T09:06:30Z', submissionId: syntheticSubmissionId },
  {
    kind: 'clarification',
    occurredAt: '2026-03-09T09:07:00Z',
    submissionId: syntheticSubmissionId,
  },
  { kind: 'renewal', occurredAt: '2026-03-09T09:08:00Z', submissionId: syntheticSubmissionId },
  { kind: 'cancelled', occurredAt: '2026-03-09T09:09:00Z', submissionId: syntheticSubmissionId },
];
