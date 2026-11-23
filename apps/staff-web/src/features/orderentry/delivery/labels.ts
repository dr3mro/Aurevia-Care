import type { Locale } from '../../../i18n/messages';

/**
 * Labels for the final delivery step. The shared bundle is outside this
 * feature's ownership, so this module owns a small parity-checked en/ar
 * dictionary (UX-01). Every visible word in the step comes from here.
 */
export const deliveryKeys = [
  'title',
  'modeLegend',
  'partnerLabel',
  'modeElectronic',
  'modePrint',
  'modeBoth',
  'receivesElectronic',
  'receivesPrint',
  'receivesBoth',
  'confirmElectronic',
  'confirmPrint',
  'confirmBoth',
  'sending',
  'timelineTitle',
  'milestonePlaced',
  'milestoneTransmitted',
  'milestoneDelivered',
  'milestonePrinted',
  'sendFailedTitle',
  'sendFailedDetail',
  'sendAmbiguousTitle',
  'sendAmbiguousDetail',
  'reference',
  'retry',
  'retryUnavailable',
  'reconcile',
  'printInstead',
] as const;

/** One label key shared by both delivery-step message bundles. */
export type DeliveryMessageKey = (typeof deliveryKeys)[number];

/** Bilingual delivery-step labels with identical key sets. */
export const deliveryMessages: Record<Locale, Record<DeliveryMessageKey, string>> = {
  en: {
    title: 'Choose how this order is delivered',
    modeLegend: 'Delivery mode',
    partnerLabel: 'Destination partner',
    modeElectronic: 'Send electronically',
    modePrint: 'Print a handout',
    modeBoth: 'Send electronically and print',
    receivesElectronic:
      'The partner receives the order electronically. The patient receives no paper handout from this step.',
    receivesPrint:
      'No order is sent electronically. The patient carries a printed handout to the performer.',
    receivesBoth:
      'The partner receives the order electronically and the patient also carries a printed handout.',
    confirmElectronic: 'Confirm: send electronically to the partner',
    confirmPrint: 'Confirm: print a handout for the patient to carry',
    confirmBoth: 'Confirm: send electronically and print a handout',
    sending: 'Sending the order…',
    timelineTitle: 'Delivery timeline',
    milestonePlaced: 'Order placed',
    milestoneTransmitted: 'Sent to the partner',
    milestoneDelivered: 'Delivered to the partner',
    milestonePrinted: 'Printed handout ready',
    sendFailedTitle: 'The order could not be sent',
    sendFailedDetail:
      'The partner did not accept the order. Retry the send when it is safe, or print a handout instead.',
    sendAmbiguousTitle: 'Delivery status is unknown',
    sendAmbiguousDetail:
      'We cannot tell whether the partner received the order. Do not resend; reconcile the delivery first or print a handout instead.',
    reference: 'Reference',
    retry: 'Retry send',
    retryUnavailable: 'An automatic retry is not safe for this failure. Print a handout instead.',
    reconcile: 'Reconcile delivery',
    printInstead: 'Print instead',
  },
  ar: {
    title: 'اختر طريقة إرسال هذا الطلب',
    modeLegend: 'طريقة الإرسال',
    partnerLabel: 'الجهة المستلمة',
    modeElectronic: 'إرسال إلكتروني',
    modePrint: 'طباعة مطبوع',
    modeBoth: 'إرسال إلكتروني وطباعة',
    receivesElectronic: 'تستلم الجهة الطلب إلكترونياً. لا يحصل المريض على مطبوع ورقي من هذه الخطوة.',
    receivesPrint: 'لا يُرسل الطلب إلكترونياً. يحمل المريض مطبوعاً إلى المنفّذ.',
    receivesBoth: 'تستلم الجهة الطلب إلكترونياً ويحمل المريض مطبوعاً أيضاً.',
    confirmElectronic: 'تأكيد: الإرسال إلكترونياً إلى الجهة',
    confirmPrint: 'تأكيد: طباعة مطبوع يحمله المريض',
    confirmBoth: 'تأكيد: الإرسال إلكترونياً وطباعة مطبوع',
    sending: 'جارٍ إرسال الطلب…',
    timelineTitle: 'خط سير الإرسال',
    milestonePlaced: 'تم تقديم الطلب',
    milestoneTransmitted: 'أُرسل إلى الجهة',
    milestoneDelivered: 'تم التسليم إلى الجهة',
    milestonePrinted: 'المطبوع جاهز',
    sendFailedTitle: 'تعذّر إرسال الطلب',
    sendFailedDetail:
      'لم تقبل الجهة الطلب. أعد الإرسال عندما يكون ذلك آمناً، أو اطبع مطبوعاً بدلاً من ذلك.',
    sendAmbiguousTitle: 'حالة الإرسال غير معروفة',
    sendAmbiguousDetail:
      'لا يمكننا التأكد ما إذا كانت الجهة استلمت الطلب. لا تُعد الإرسال؛ تحقق من الإرسال أولاً أو اطبع مطبوعاً بدلاً من ذلك.',
    reference: 'المرجع',
    retry: 'إعادة الإرسال',
    retryUnavailable: 'إعادة الإرسال التلقائي غير آمنة لهذا الفشل. اطبع مطبوعاً بدلاً من ذلك.',
    reconcile: 'التحقق من الإرسال',
    printInstead: 'الطباعة بدلاً من ذلك',
  },
};

/** Look up one delivery-step label for the active locale. */
export function deliveryText(locale: Locale, key: DeliveryMessageKey): string {
  return deliveryMessages[locale][key];
}
