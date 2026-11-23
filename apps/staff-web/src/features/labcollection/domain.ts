import type { PersonName } from '../../i18n/format';

/** The authoritative lifecycle states of a lab accession (LAB-01). */
export type AccessionState = 'accessioned' | 'collected' | 'received' | 'rejected' | 'recollected';

/**
 * One accession as shown by the collection desk. Names carry both script forms
 * where the record has them so an identity check can show two names, never a
 * single transliteration. All identifiers are opaque and synthetic in tests.
 */
export interface CollectionOrder {
  organizationId: string;
  id: string;
  accessionNo: string;
  accessionIssuer: string;
  patientId: string;
  patientName: PersonName;
  patientMrn: string;
  patientMrnIssuer: string;
  sourceOrderRef: string;
  state: AccessionState;
  version: number;
}

/** The identity a collector reads from the wristband and requisition. */
export interface PresentedIdentity {
  patientId: string;
  accessionNo: string;
  accessionIssuer: string;
}

/** A reason the presented identity did not match the order. */
export type MismatchReason = 'patient' | 'accession-number' | 'accession-issuer';

/** Outcome of comparing the presented identity with the opened order. */
export interface MatchCheck {
  matched: boolean;
  reasons: readonly MismatchReason[];
}

/**
 * Compare the identity presented at the bedside with the opened order. Every
 * mismatch is returned so the screen can explain exactly what to recheck; a
 * match alone never records a collection.
 *
 * @param order order opened by the collector
 * @param presented identifiers read from the wristband and requisition
 * @returns whether all three identifiers match and the reasons that do not
 */
export function checkMatch(order: CollectionOrder, presented: PresentedIdentity): MatchCheck {
  const reasons: MismatchReason[] = [];
  if (presented.patientId !== order.patientId) {
    reasons.push('patient');
  }
  if (presented.accessionNo !== order.accessionNo) {
    reasons.push('accession-number');
  }
  if (presented.accessionIssuer !== order.accessionIssuer) {
    reasons.push('accession-issuer');
  }
  return { matched: reasons.length === 0, reasons };
}

/** Which identifier the collector is searching an order by. */
export type FindMode = 'accession' | 'patient';

/**
 * Filter the loaded orders by accession number or by patient. Patient search
 * covers the opaque identifier, the Latin name and the local-script name so a
 * collector can find an order from any form printed on the requisition.
 *
 * @param orders orders loaded for the active organization
 * @param mode accession or patient search
 * @param query free text; blank text yields no results
 * @returns matching orders in the loaded order
 */
export function findOrders(
  orders: readonly CollectionOrder[],
  mode: FindMode,
  query: string,
): CollectionOrder[] {
  const needle = query.trim().toLowerCase();
  if (needle === '') {
    return [];
  }
  return orders.filter((order) =>
    mode === 'accession'
      ? order.accessionNo.toLowerCase().includes(needle)
      : order.patientId.toLowerCase().includes(needle) ||
        order.patientMrn.toLowerCase().includes(needle) ||
        order.patientName.latinName.toLowerCase().includes(needle) ||
        (order.patientName.localName?.toLowerCase().includes(needle) ?? false),
  );
}

/** The five bounded rejection reason codes accepted by the LABFLOW-02 API. */
export const rejectionReasonCodes = [
  'haemolysed',
  'wrong_tube',
  'unlabelled',
  'insufficient_volume',
  'other',
] as const;

/** One of the bounded rejection reason codes. */
export type RejectionReasonCode = (typeof rejectionReasonCodes)[number];

/** Outcome of validating a rejection before it is sent. */
export type RejectionValidation =
  { valid: true } | { valid: false; error: 'reason-required' | 'detail-required' };

/**
 * Validate a rejection locally so an empty reason or an `other` reason without
 * detail never reaches the API. The server remains the authority on vocabulary.
 *
 * @param reason selected reason code
 * @param detail free-text detail required only for `other`
 * @returns a valid result or the specific reason it was rejected for
 */
export function validateRejection(reason: string, detail: string): RejectionValidation {
  if (reason === '') {
    return { valid: false, error: 'reason-required' };
  }
  if (reason === 'other' && detail.trim() === '') {
    return { valid: false, error: 'detail-required' };
  }
  return { valid: true };
}

/** Specimen details entered by the collector at the bedside. */
export interface SpecimenDetails {
  specimenType: string;
  siteCode: string;
  collector: string;
  collectedAt: string;
  quality: string;
}

/** The local rejection draft before it is validated and sent. */
export interface RejectionDraft {
  reason: string;
  detail: string;
  notifyPatient: boolean;
}

/** Print-ready label data returned by the collection API (LABFLOW-01). */
export interface LabelData {
  accessionNo: string;
  accessionIssuer: string;
  patientName: string;
  patientMrn: string;
  patientMrnIssuer: string;
  specimenType: string;
  collectedAt: string;
}

/** One keyed value printed on the label sheet. */
export interface LabelRow {
  key: LabelFieldKey;
  value: string;
}

/** The required fields of a printed specimen label. */
export type LabelFieldKey =
  | 'accessionNo'
  | 'accessionIssuer'
  | 'patientName'
  | 'patientMrn'
  | 'patientMrnIssuer'
  | 'specimenType'
  | 'collectedAt';

/**
 * Present the label data in a fixed, reviewable order. The list is the single
 * definition of "required fields" used by the sheet and its test.
 *
 * @param label label data returned for a collected specimen
 * @returns the required field rows in print order
 */
export function labelRows(label: LabelData): readonly LabelRow[] {
  return [
    { key: 'accessionNo', value: label.accessionNo },
    { key: 'accessionIssuer', value: label.accessionIssuer },
    { key: 'patientName', value: label.patientName },
    { key: 'patientMrn', value: label.patientMrn },
    { key: 'patientMrnIssuer', value: label.patientMrnIssuer },
    { key: 'specimenType', value: label.specimenType },
    { key: 'collectedAt', value: label.collectedAt },
  ];
}
