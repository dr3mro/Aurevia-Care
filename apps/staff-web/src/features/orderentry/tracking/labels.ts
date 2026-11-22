import type { Locale } from '../../../i18n/messages';
import type { EntryMessageKey } from '../entry/messages';
import type { TrackedOrderStatus } from './domain';

/**
 * Orders-tab labels. This is a `labels.ts` module rather than a `messages.ts`
 * bundle because the repository-wide message registry under `tests/` discovers
 * only files named `messages.ts` / `stateViewMessages.ts`; keeping the name used
 * by other self-contained features (for example `radreferrer/labels.ts`) avoids
 * editing a test file outside this story's scope.
 */
export const trackingKeys = [
  'ordersTab',
  'syntheticNotice',
  'noOrders',
  'colOrder',
  'colStatus',
  'colDelivery',
  'colResult',
  'colActions',
  'statusDraft',
  'statusPlaced',
  'statusDelivered',
  'statusAccepted',
  'statusCollectedOrScheduled',
  'statusResulted',
  'statusCancelled',
  'cancelRequested',
  'delivered',
  'notDelivered',
  'partnerReference',
  'openResult',
  'noResult',
  'cancel',
  'amend',
  'reason',
  'required',
  'reasonRequired',
  'discard',
  'cancelledNotice',
  'cancelRequestSentNotice',
  'amendNotice',
  'sendError',
] as const;

/** One label key shared by both orders-tab bundles. */
export type TrackingMessageKey = (typeof trackingKeys)[number];

/** English and Arabic orders-tab labels with identical key sets. */
export const trackingMessages: Record<Locale, Record<TrackingMessageKey, string>> = {
  en: {
    ordersTab: 'Orders',
    syntheticNotice: 'Synthetic orders and results only. No real command is sent.',
    noOrders: 'No orders are recorded on this chart.',
    colOrder: 'Order',
    colStatus: 'Status',
    colDelivery: 'Delivery evidence',
    colResult: 'Result',
    colActions: 'Actions',
    statusDraft: 'Draft',
    statusPlaced: 'Placed',
    statusDelivered: 'Delivered',
    statusAccepted: 'Accepted',
    statusCollectedOrScheduled: 'Collected / scheduled',
    statusResulted: 'Resulted',
    statusCancelled: 'Cancelled',
    cancelRequested: 'Cancellation requested — awaiting partner',
    delivered: 'Delivered',
    notDelivered: 'Not delivered yet',
    partnerReference: 'Partner reference',
    openResult: 'Open result',
    noResult: 'No result yet',
    cancel: 'Cancel',
    amend: 'Amend',
    reason: 'Reason',
    required: 'required',
    reasonRequired: 'A reason is required before this action can be sent.',
    discard: 'Discard',
    cancelledNotice: 'The order was cancelled.',
    cancelRequestSentNotice:
      'A cancellation request was sent to the partner. The order stays accepted until the partner replies.',
    amendNotice: 'An amendment was sent to the partner.',
    sendError: 'The action could not be sent. Try again.',
  },
  ar: {
    ordersTab: 'الطلبات',
    syntheticNotice: 'طلبات ونتائج توضيحية فقط؛ لا يتم إرسال أي أمر حقيقي.',
    noOrders: 'لا توجد طلبات مسجلة في هذا الملف.',
    colOrder: 'الطلب',
    colStatus: 'الحالة',
    colDelivery: 'دليل الإرسال',
    colResult: 'النتيجة',
    colActions: 'الإجراءات',
    statusDraft: 'مسودة',
    statusPlaced: 'مُصدَر',
    statusDelivered: 'تم الإرسال',
    statusAccepted: 'مقبول',
    statusCollectedOrScheduled: 'تم جمع العينة / مجدول',
    statusResulted: 'توفّرت النتيجة',
    statusCancelled: 'ملغي',
    cancelRequested: 'طلب إلغاء — بانتظار رد الجهة',
    delivered: 'تم الإرسال',
    notDelivered: 'لم يتم الإرسال بعد',
    partnerReference: 'مرجع الجهة',
    openResult: 'فتح النتيجة',
    noResult: 'لا توجد نتيجة بعد',
    cancel: 'إلغاء',
    amend: 'تعديل',
    reason: 'السبب',
    required: 'مطلوب',
    reasonRequired: 'يلزم إدخال سبب قبل إرسال هذا الإجراء.',
    discard: 'تجاهل',
    cancelledNotice: 'تم إلغاء الطلب.',
    cancelRequestSentNotice: 'تم إرسال طلب إلغاء إلى الجهة. يبقى الطلب مقبولاً حتى ترد الجهة.',
    amendNotice: 'تم إرسال تعديل إلى الجهة.',
    sendError: 'تعذّر إرسال الإجراء. حاول مرة أخرى.',
  },
};

/** Look up one orders-tab label for the active locale. */
export function trackingText(locale: Locale, key: TrackingMessageKey): string {
  return trackingMessages[locale][key];
}

/** Status-to-label mapping; every wire status has its own wording. */
const STATUS_LABEL: Readonly<Record<TrackedOrderStatus, TrackingMessageKey>> = {
  draft: 'statusDraft',
  placed: 'statusPlaced',
  delivered: 'statusDelivered',
  accepted: 'statusAccepted',
  collected_or_scheduled: 'statusCollectedOrScheduled',
  resulted: 'statusResulted',
  cancelled: 'statusCancelled',
};

/** Resolve the translated wording key for one order status. */
export function statusMessageKey(status: TrackedOrderStatus): TrackingMessageKey {
  return STATUS_LABEL[status];
}

/** Reuse the order-entry delivery wording instead of duplicating a vocabulary. */
export function deliveryModeMessageKey(mode: 'electronic' | 'print' | 'both'): EntryMessageKey {
  return mode;
}
