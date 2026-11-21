/**
 * Wire and view types for the pharmacy prescription inbox (RX-02, WEB-09).
 *
 * Field names are taken from the pharmacy inbox and status API projections, not
 * invented here. Every record is read-only so a screen cannot mutate a response
 * that belongs to the active tenant.
 */

/** Stable prescription states published by the prescribing channel. */
export const PRESCRIPTION_STATES = [
  'draft',
  'attested',
  'submitted',
  'ambiguous',
  'delivery_confirmed',
  'accepted',
  'rejected',
  'clarification',
  'dispensed',
  'printed',
  'cancelled',
] as const;

/** One prescription state as a lowercase wire key. */
export type PrescriptionState = (typeof PRESCRIPTION_STATES)[number];

/** Status filters offered by the inbox; `all` omits the query parameter. */
export const STATUS_FILTERS = [
  'all',
  'submitted',
  'delivery_confirmed',
  'accepted',
  'rejected',
  'clarification',
  'dispensed',
] as const;

/** One inbox status filter. */
export type StatusFilter = (typeof STATUS_FILTERS)[number];

/**
 * The chart-free prescription snapshot a pharmacy is allowed to see. The opaque
 * patient id is a dispensing reference, never chart content.
 */
export interface InboxItemResponse {
  id: string;
  version: number;
  patientId: string;
  medication: string;
  strength: string;
  dose: string;
  route: string;
  frequency: string;
  quantity: number;
  state: PrescriptionState;
  submissionId: string;
  prescriberSubject: string;
  destinationPharmacy: string;
  createdAt: string;
}

/** One cursor-paginated page of routed prescriptions, newest first. */
export interface InboxPageResponse {
  items: readonly InboxItemResponse[];
  nextCursor: string | null;
}

/** One immutable pharmacy status fact; detail carries a reason or question. */
export interface StatusEventResponse {
  id: string;
  organizationId: string;
  prescriptionId: string;
  version: number;
  type: string;
  actorId: string;
  occurredAt: string;
  detail: string | null;
  dispensedBy: string | null;
  dispensedAt: string | null;
  handedOver: string | null;
}

/** Current pharmacy status and its complete append-only fact trail. */
export interface StatusViewResponse {
  organizationId: string;
  prescriptionId: string;
  version: number;
  accepted: boolean;
  dispensed: boolean;
  events: readonly StatusEventResponse[];
}

/** The separate evidence a pharmacy records when it hands over a medicine. */
export interface DispensingEvidence {
  dispensedBy: string;
  dispensedAt: string;
  handedOver: string;
}

/** The two reads that make up one prescription detail screen. */
export interface PharmacyDetail {
  item: InboxItemResponse;
  status: StatusViewResponse;
}
