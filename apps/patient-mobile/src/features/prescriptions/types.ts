/**
 * Patient-visible prescription projection (RX-02, PAT-02).
 *
 * Only the plain status projection and the events needed to derive it are held
 * here. No rendered clinical bytes, credential or durable record is part of this
 * model, so the app never keeps a persistent clinical cache.
 */

/** Display locale for the prescription screen (UX-01). */
export type PrescriptionLocale = 'en' | 'ar';

/**
 * Transmission and pharmacy events tracked separately for a prescription (RX-02).
 *
 * Pharmacy acceptance and dispensing evidence are deliberately distinct kinds:
 * acceptance is not evidence that medicine was handed over.
 */
export type PrescriptionEventKind =
  | 'submitted'
  | 'deliveryConfirmed'
  | 'accepted'
  | 'clarificationRequested'
  | 'rejected'
  | 'renewal'
  | 'cancelled'
  | 'dispensingEvidence';

/** One append-only event with its actor and UTC instant (synthetic in fixtures). */
export interface PrescriptionEvent {
  readonly kind: PrescriptionEventKind;
  readonly at: string;
  readonly actor: string;
}

/** One released prescription shown to the patient. */
export interface ReleasedPrescription {
  readonly prescriptionId: string;
  /** Stable submission identifier shown so the patient can match it to their prescription. */
  readonly reference: string;
  /** Plain medication label; never a rendered clinical document. */
  readonly medicationLabel: string;
  readonly language: PrescriptionLocale;
  readonly events: readonly PrescriptionEvent[];
}

/** The four plain status words a patient may see (RX-02). */
export type PrescriptionStatus = 'sent' | 'accepted' | 'needsCheck' | 'handedOver';
