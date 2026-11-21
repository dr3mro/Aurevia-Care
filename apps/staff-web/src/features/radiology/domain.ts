/**
 * Radiography domain model and pure workflow rules (RAD-01).
 *
 * The signed interpretation is immutable: an addendum is a separate linked
 * fact, and the referrer view is always derived from the signed original plus
 * its addenda. Study association is refused unless both the patient and the
 * accession number match the scheduled order.
 */

import type { PersonName } from '../../i18n/format';

/** The lifecycle of one imaging order (RAD-01). */
export type ImagingOrderState =
  | 'received'
  | 'scheduled'
  | 'study-linked'
  | 'interpreted'
  | 'addended';

/** A supported imaging modality captured at scheduling time. */
export type Modality = 'CT' | 'MRI' | 'XR' | 'US' | 'PET' | 'NM';

/** The complete set of modalities a schedule step may send (defensive boundary). */
export const MODALITIES: readonly Modality[] = ['CT', 'MRI', 'XR', 'US', 'PET', 'NM'];

/** Narrow free text to a supported modality before it reaches the API. */
export function isModality(value: string): value is Modality {
  return MODALITIES.some((modality) => modality === value);
}

/** Clinical urgency captured when the order is received. */
export type Urgency = 'routine' | 'urgent' | 'stat';

/** A linked, signed addendum. The signed original is never edited. */
export interface Addendum {
  id: string;
  text: string;
  author: string;
  addedAt: string;
}

/** The signed interpretation and its append-only addenda (RAD-01). */
export interface Interpretation {
  technique: string;
  comparison: string;
  findings: string;
  impression: string;
  followUp: string;
  signedBy: string;
  signedAt: string;
  addenda: readonly Addendum[];
}

/** One imaging order as displayed by the staff screen. */
export interface ImagingOrder {
  id: string;
  version: number;
  patientId: string;
  patientName: PersonName;
  urgency: Urgency;
  clinicalQuestion: string;
  state: ImagingOrderState;
  accessionNo: string | null;
  modality: Modality | null;
  scheduledAt: string | null;
  studyInstanceUid: string | null;
  interpretation: Interpretation | null;
}

/** External study association entered by the radiology user. */
export interface StudyLink {
  studyInstanceUid: string;
  patientId: string;
  accessionNo: string;
}

/** The outcome of the mandatory patient/accession check before association. */
export type StudyLinkCheck =
  | { ok: true }
  | { ok: false; patientMismatch: boolean; accessionMismatch: boolean };

/** Details captured by the schedule step. */
export interface ScheduleInput {
  accessionNo: string;
  modality: Modality;
  scheduledAt: string;
}

/** Free-text sections captured before an interpretation is signed. */
export interface InterpretationInput {
  technique: string;
  comparison: string;
  findings: string;
  impression: string;
  followUp: string;
}

/** The immutable view handed to the referring clinician. */
export interface ReferrerView {
  orderId: string;
  patientId: string;
  signedBy: string;
  signedAt: string;
  findings: string;
  impression: string;
  addenda: readonly Addendum[];
}

/**
 * Verify that an external study belongs to this order.
 *
 * Both the patient and the accession number must match. A mismatch is reported
 * per field so the screen can explain a wrong-patient link precisely; the
 * caller must not persist the association when `ok` is false.
 */
export function verifyStudyLink(order: ImagingOrder, study: StudyLink): StudyLinkCheck {
  const patientMismatch = study.patientId !== order.patientId;
  const accessionMismatch = study.accessionNo !== order.accessionNo;
  if (!patientMismatch && !accessionMismatch) {
    return { ok: true };
  }
  return { ok: false, patientMismatch, accessionMismatch };
}

/** A new order may only be scheduled from the received state. */
export function canSchedule(order: ImagingOrder): boolean {
  return order.state === 'received';
}

/** A study may only be linked to a scheduled order with an accession and time. */
export function canLinkStudy(order: ImagingOrder): boolean {
  return order.state === 'scheduled' && order.accessionNo !== null && order.scheduledAt !== null;
}

/** An interpretation may only be signed once a study is linked. */
export function canInterpret(order: ImagingOrder): boolean {
  return order.state === 'study-linked' && order.studyInstanceUid !== null;
}

/** Return a new order with the schedule step recorded. */
export function scheduleOrder(order: ImagingOrder, input: ScheduleInput): ImagingOrder {
  return {
    ...order,
    state: 'scheduled',
    accessionNo: input.accessionNo,
    modality: input.modality,
    scheduledAt: input.scheduledAt,
  };
}

/** Return a new order with a verified study association. */
export function linkStudyToOrder(order: ImagingOrder, study: StudyLink): ImagingOrder {
  return {
    ...order,
    state: 'study-linked',
    studyInstanceUid: study.studyInstanceUid,
  };
}

/** Create a signed interpretation with an empty addenda list. */
export function signInterpretation(
  input: InterpretationInput,
  signedBy: string,
  signedAt: string,
): Interpretation {
  return {
    technique: input.technique,
    comparison: input.comparison,
    findings: input.findings,
    impression: input.impression,
    followUp: input.followUp,
    signedBy,
    signedAt,
    addenda: [],
  };
}

/** Return a new order carrying the signed interpretation. */
export function signInterpretationForOrder(
  order: ImagingOrder,
  interpretation: Interpretation,
): ImagingOrder {
  return { ...order, state: 'interpreted', interpretation };
}

/** Return a new interpretation with one addendum appended, leaving the signed fields intact. */
export function withAddendum(
  interpretation: Interpretation,
  addendum: Addendum,
): Interpretation {
  return {
    ...interpretation,
    addenda: [...interpretation.addenda, addendum],
  };
}

/** Return a new order with an addendum appended to its signed interpretation. */
export function appendAddendum(order: ImagingOrder, addendum: Addendum): ImagingOrder {
  const signed = order.interpretation!;
  return {
    ...order,
    state: 'addended',
    interpretation: withAddendum(signed, addendum),
  };
}

/** Derive the referrer-facing view; no view exists before signing. */
export function buildReferrerView(order: ImagingOrder): ReferrerView | null {
  const interpretation = order.interpretation;
  if (interpretation === null) {
    return null;
  }
  return {
    orderId: order.id,
    patientId: order.patientId,
    signedBy: interpretation.signedBy,
    signedAt: interpretation.signedAt,
    findings: interpretation.findings,
    impression: interpretation.impression,
    addenda: interpretation.addenda,
  };
}
