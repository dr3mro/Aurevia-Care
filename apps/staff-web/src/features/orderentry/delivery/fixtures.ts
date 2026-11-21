import type { DeliveryMilestone, SendResult } from './domain';

/** Synthetic partner names; no real organization or contract is represented. */
export const syntheticPartnerName = 'Synthetic Lab Partner';
export const syntheticArabicPartnerName = 'شريك المختبر التجريبي';

/** Synthetic recorded timelines for an electronic and a print-only send. */
export const syntheticTimeline: readonly DeliveryMilestone[] = [
  'placed',
  'transmitted',
  'delivered',
];
export const syntheticPrintTimeline: readonly DeliveryMilestone[] = ['placed', 'printed'];

/** Synthetic send outcomes with opaque correlation ids. */
export const failedSend: SendResult = {
  state: 'failed',
  retrySafe: true,
  correlationId: 'SYN-CORR-42',
};
export const unsafeFailedSend: SendResult = {
  state: 'failed',
  retrySafe: false,
  correlationId: 'SYN-CORR-44',
};
export const ambiguousSend: SendResult = {
  state: 'ambiguous',
  correlationId: 'SYN-CORR-43',
};
