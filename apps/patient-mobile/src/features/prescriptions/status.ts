/**
 * Derive the plain, patient-visible status of a released prescription (RX-02).
 *
 * The single clinical-safety rule is that a pharmacy acceptance is not
 * dispensing evidence: only a `dispensingEvidence` event may yield the
 * "Handed over" word. Clarification and rejection yield the "needs to check"
 * word; everything else remains "Sent to pharmacy".
 */
import type { PillVariant } from '../appointments/patientDesign';

import { STATUS_WORDS } from './messages';
import type { PrescriptionEvent, PrescriptionLocale, PrescriptionStatus, ReleasedPrescription } from './types';

/**
 * Map a prescription's events to one of the four plain status words.
 *
 * @param prescription released prescription with its append-only events
 * @returns the single status the patient should see
 */
export function plainStatusFor(prescription: ReleasedPrescription): PrescriptionStatus {
  const kinds = new Set<PrescriptionEvent['kind']>(prescription.events.map((event) => event.kind));
  if (kinds.has('dispensingEvidence')) {
    return 'handedOver';
  }
  if (kinds.has('clarificationRequested') || kinds.has('rejected')) {
    return 'needsCheck';
  }
  if (kinds.has('renewal')) {
    return 'sent';
  }
  if (kinds.has('accepted')) {
    return 'accepted';
  }
  return 'sent';
}

/**
 * Whether a prescription has a terminal cancellation event (RX-02).
 *
 * A cancelled prescription is not an active released prescription and must be
 * excluded before display, so it can never be shown as an in-progress or
 * handed-over status.
 *
 * @param prescription released prescription with its events
 * @returns true when a cancellation event is present
 */
export function isCancelled(prescription: ReleasedPrescription): boolean {
  return prescription.events.some((event) => event.kind === 'cancelled');
}

/**
 * Look up the translated plain status word.
 *
 * @param locale active display locale
 * @param status derived prescription status
 * @returns the localized status word
 */
export function statusLabel(locale: PrescriptionLocale, status: PrescriptionStatus): string {
  return STATUS_WORDS[status][locale];
}

/**
 * Choose a reinforcement colour for a status pill; the word always carries the
 * meaning on its own (UX-01). Only dispensing evidence uses the success colour.
 *
 * @param status derived prescription status
 * @returns the pill variant
 */
export function pillVariantFor(status: PrescriptionStatus): PillVariant {
  switch (status) {
    case 'handedOver':
      return 'success';
    case 'needsCheck':
      return 'warning';
    case 'accepted':
      return 'info';
    case 'sent':
      return 'neutral';
  }
}

/**
 * Whether the prescription must be laid out right-to-left (UX-01).
 *
 * @param language language of the prescription projection
 * @returns true for Arabic
 */
export function isRtl(language: PrescriptionLocale): boolean {
  return language === 'ar';
}
