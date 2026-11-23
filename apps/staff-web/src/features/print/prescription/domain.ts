/**
 * Pure print-eligibility rules for a prescription handout (RX-02).
 *
 * These mirror the server's `HandoutPolicy`: a prescription is printable from a
 * bounded set of states beyond draft and ambiguous, must carry an attestation,
 * and must have an approved BUILD-10 channel. The server remains authoritative;
 * this check only decides whether the button is enabled and why it is not.
 */

import type { PrescriptionState } from '../../pharmacyinbox/types';
import { PRINT_CHANNELS } from './types';
import type { PrescriptionCopy, PrescriptionForPrint, PrintEligibility } from './types';

/** Prescription states a handout may be printed from, in wire-key form. */
const PRINTABLE_STATES: ReadonlySet<PrescriptionState> = new Set([
  'attested',
  'submitted',
  'delivery_confirmed',
  'accepted',
  'clarification',
  'dispensed',
  'printed',
]);

const APPROVED_CHANNELS: ReadonlySet<string> = new Set(PRINT_CHANNELS);

/** Decide whether the print button is enabled and, if not, the reason in words. */
export function printEligibility(prescription: PrescriptionForPrint): PrintEligibility {
  if (prescription.state === 'draft') {
    return { allowed: false, blocker: 'draft' };
  }
  if (!PRINTABLE_STATES.has(prescription.state)) {
    return { allowed: false, blocker: 'not-printable' };
  }
  if (!prescription.attested) {
    return { allowed: false, blocker: 'not-attested' };
  }
  if (prescription.printChannel === null || !APPROVED_CHANNELS.has(prescription.printChannel)) {
    return { allowed: false, blocker: 'channel-not-approved' };
  }
  return { allowed: true };
}

/** True when a handout version is already bound to this source revision. */
export function isReprint(prescription: PrescriptionForPrint): boolean {
  return prescription.handoutVersion !== null;
}

/**
 * Copies offered for this prescription. A locally printable channel has no
 * electronic submission, so the pharmacy needs its own printed copy; an
 * electronic channel already reaches the pharmacy and offers only the patient
 * copy (RX-02, docs/operations/printing/README.md).
 */
export function offeredCopies(prescription: PrescriptionForPrint): readonly PrescriptionCopy[] {
  return prescription.printChannel === 'PRINTABLE_LOCAL' ? ['patient', 'pharmacy'] : ['patient'];
}
