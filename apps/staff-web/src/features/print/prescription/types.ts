/**
 * Bounded types for the prescription print button (RX-02, WEB-09).
 *
 * Field names are taken from the authoritative Rx/print API projections, not
 * invented here. The button is presentational: the owning screen supplies the
 * immutable handout URL and records the print through `onRecord`.
 */

import type { HandoutLanguage, PrintReason } from '../components/messages';
import type { PrescriptionState } from '../../pharmacyinbox/types';

/** Approved RX-02 print channel wire keys (RxChannel.java, HandoutPolicy). */
export const PRINT_CHANNELS = ['E_PRESCRIBE', 'PRINTABLE_LOCAL'] as const;

/** One approved prescription print channel. */
export type PrescriptionPrintChannel = (typeof PRINT_CHANNELS)[number];

/** Which handout copy the clinician is producing. */
export type PrescriptionCopy = 'patient' | 'pharmacy';

/**
 * The read-only prescription snapshot needed to decide print eligibility.
 * `handoutVersion` is the immutable handout already bound to this exact source
 * revision; a non-null value means the next print is a reprint.
 */
export interface PrescriptionForPrint {
  id: string;
  version: number;
  state: PrescriptionState;
  attested: boolean;
  printChannel: string | null;
  handoutVersion: number | null;
}

/** Why a prescription cannot be printed, shown to the user in words. */
export type PrescriptionPrintBlocker =
  'draft' | 'not-printable' | 'not-attested' | 'channel-not-approved';

/** Result of the client-side eligibility check that gates the button. */
export type PrintEligibility =
  { allowed: true } | { allowed: false; blocker: PrescriptionPrintBlocker };

/** One confirmed print or download; the owning screen records it as the audit. */
export interface PrescriptionPrintRecord {
  prescriptionId: string;
  sourceVersion: number;
  copy: PrescriptionCopy;
  language: HandoutLanguage;
  reprint: boolean;
  reason: PrintReason | null;
  action: 'print' | 'download';
}

/** Props for the reusable prescription print button. */
export interface PrintPrescriptionButtonProps {
  prescription: PrescriptionForPrint;
  /** URL of the immutable handout PDF for this exact source revision. */
  handoutUrl: string;
  /** Called when a print or download is confirmed; the owner records the reason. */
  onRecord: (record: PrescriptionPrintRecord) => void;
}
