import type { Locale } from '../../../i18n/messages';
import type { DeliveryMode } from '../entry/domain';
import type { DeliveryMessageKey } from './labels';

/** The three delivery modes, in the order offered to the clinician (LAB-01, RAD-01). */
export const deliveryModes: readonly DeliveryMode[] = ['electronic', 'print', 'both'];

/** A recorded step in the delivery timeline shown after an order is placed (XCH-02). */
export type DeliveryMilestone = 'placed' | 'transmitted' | 'delivered' | 'printed';

/**
 * Outcome of the final send, supplied by the owning container from the delivery
 * API. The step never infers or mutates it: whether a retry is safe is a server
 * policy decision (ARC-05), carried explicitly on a known failure.
 */
export type SendResult =
  | { state: 'idle' }
  | { state: 'sending' }
  | { state: 'placed'; timeline: readonly DeliveryMilestone[] }
  | { state: 'failed'; retrySafe: boolean; correlationId?: string | undefined }
  | { state: 'ambiguous'; correlationId?: string | undefined };

/** True only when the container says the failure may be retried without duplicating an order. */
export function canRetry(result: SendResult): boolean {
  return result.state === 'failed' && result.retrySafe;
}

/** True when the send needs a recovery decision instead of the final confirm. */
export function needsRecovery(
  result: SendResult,
): result is Extract<SendResult, { state: 'failed' | 'ambiguous' }> {
  return result.state === 'failed' || result.state === 'ambiguous';
}

/** Which worded description of the patient's handout a mode uses. */
export const receivesKey: Record<DeliveryMode, DeliveryMessageKey> = {
  electronic: 'receivesElectronic',
  print: 'receivesPrint',
  both: 'receivesBoth',
};

/** Which one-sentence confirm label states a mode in words. */
export const confirmKey: Record<DeliveryMode, DeliveryMessageKey> = {
  electronic: 'confirmElectronic',
  print: 'confirmPrint',
  both: 'confirmBoth',
};

/** Which radio-option label a mode uses. */
export const modeLabelKey: Record<DeliveryMode, DeliveryMessageKey> = {
  electronic: 'modeElectronic',
  print: 'modePrint',
  both: 'modeBoth',
};

/** Which worded label a recorded delivery milestone uses. */
export const milestoneKey: Record<DeliveryMilestone, DeliveryMessageKey> = {
  placed: 'milestonePlaced',
  transmitted: 'milestoneTransmitted',
  delivered: 'milestoneDelivered',
  printed: 'milestonePrinted',
};

/**
 * Language tag for a partner name. A name in Arabic script is read with an
 * Arabic voice even when the interface is English; a Latin name follows the
 * interface locale (UX-01).
 */
export function partnerLang(name: string, locale: Locale): Locale {
  return /[\u0600-\u06FF]/.test(name) ? 'ar' : locale;
}
