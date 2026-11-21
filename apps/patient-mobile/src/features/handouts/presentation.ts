/**
 * Pure presentation rules for released handouts (PAT-02, CLN-07, UX-01).
 *
 * These functions hold no network or storage state. The client owns the single
 * visibility decision at the trust boundary; the screen only formats the values
 * it receives.
 */
import type { Locale } from '../appointments/patientDesign';

import { handoutText } from './messages';
import type { HandoutKindTextKey } from './messages';
import type { HandoutKind, ReleasedHandout } from './types';

/** Map a handout kind to its message key without a switch or a hidden branch. */
const KIND_TEXT_KEY: Record<HandoutKind, HandoutKindTextKey> = {
  prescription: 'prescription',
  'order-sheet': 'orderSheet',
  'chart-summary': 'chartSummary',
};

/**
 * Keep only handouts that have actually been released to the patient.
 *
 * @param handouts parsed handout projections
 * @returns released handouts, in server order
 */
export function releasedOnly(handouts: readonly ReleasedHandout[]): ReleasedHandout[] {
  return handouts.filter((handout) => handout.releaseState === 'released');
}

/**
 * Build the Corrected note for a handout replaced by a newer version.
 *
 * Both the replacing version and the clinician's reason are required; one
 * without the other would give the patient an ambiguous correction notice
 * (PAT-02).
 *
 * @param locale active display locale
 * @param handout handout to describe
 * @returns the correction context, or `null` when no newer version replaced it
 */
export function correctedNote(locale: Locale, handout: ReleasedHandout): string | null {
  const superseded = handout.supersededBy;
  if (superseded === null) {
    return null;
  }
  return `${handoutText(locale, 'replacedBy')} ${superseded.version} — ${superseded.reason}`;
}

/**
 * Whether a handout must be laid out right-to-left (UX-01).
 *
 * @param handout handout to lay out
 * @returns true for an Arabic handout
 */
export function isRtl(handout: ReleasedHandout): boolean {
  return handout.language === 'ar';
}

/**
 * Localized label for a handout kind.
 *
 * @param locale active display locale
 * @param kind handout kind from the projection
 * @returns the translated kind label
 */
export function kindText(locale: Locale, kind: HandoutKind): string {
  return handoutText(locale, KIND_TEXT_KEY[kind]);
}
