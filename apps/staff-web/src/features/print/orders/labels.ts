import type { Locale } from '../../../i18n/messages';
import type { OrderPriority } from '../../orderentry/entry/domain';

/**
 * Sheet-only labels. Reason, language, Print and Close come from the shared
 * print bundle so the requisition and the preview dialog use one vocabulary.
 */
export const orderPrintMessageKeys = [
  'title',
  'orderNumber',
  'investigations',
  'clinicalQuestion',
  'priority',
  'priorityRoutine',
  'priorityUrgent',
  'priorityStat',
  'preparation',
  'whereToGo',
  'layoutLegend',
  'layoutCombined',
  'layoutSeparate',
  'sheetCount',
  'draftCannotPrint',
  'draftExcluded',
  'printButton',
  'closePreview',
  'reasonMissing',
] as const;

/** A label key used by the order requisition sheet and button. */
export type OrderPrintMessageKey = (typeof orderPrintMessageKeys)[number];

/** English and Arabic requisition labels with identical key sets. */
export const orderPrintMessages: Record<Locale, Record<OrderPrintMessageKey, string>> = {
  en: {
    title: 'Patient requisition',
    orderNumber: 'Order number',
    investigations: 'Tests or study',
    clinicalQuestion: 'Clinical question',
    priority: 'Priority',
    priorityRoutine: 'Routine',
    priorityUrgent: 'Urgent',
    priorityStat: 'Stat',
    preparation: 'Preparation',
    whereToGo: 'Where to go',
    layoutLegend: 'Print layout',
    layoutCombined: 'One sheet for all orders',
    layoutSeparate: 'One sheet per order',
    sheetCount: 'Sheets',
    draftCannotPrint: 'Draft orders cannot be printed. Place the order first.',
    draftExcluded: 'Draft orders are not included on the requisition.',
    printButton: 'Print for patient',
    closePreview: 'Close preview',
    reasonMissing: 'Choose why you are printing.',
  },
  ar: {
    title: 'طلب المريض',
    orderNumber: 'رقم الطلب',
    investigations: 'الفحوصات أو الدراسة',
    clinicalQuestion: 'السؤال السريري',
    priority: 'الأولوية',
    priorityRoutine: 'روتيني',
    priorityUrgent: 'عاجل',
    priorityStat: 'طفري',
    preparation: 'التحضير',
    whereToGo: 'مكان الذهاب',
    layoutLegend: 'تخطيط الطباعة',
    layoutCombined: 'ورقة واحدة لجميع الطلبات',
    layoutSeparate: 'ورقة لكل طلب',
    sheetCount: 'عدد الأوراق',
    draftCannotPrint: 'لا يمكن طباعة الطلبات المسودة. ضع الطلب أولًا.',
    draftExcluded: 'الطلبات المسودة غير مدرجة في الطلب.',
    printButton: 'طباعة للمريض',
    closePreview: 'إغلاق المعاينة',
    reasonMissing: 'اختر سبب الطباعة.',
  },
};

/** Map a coded order priority to its bilingual label key. */
export const priorityMessageKey: Record<OrderPriority, OrderPrintMessageKey> = {
  routine: 'priorityRoutine',
  urgent: 'priorityUrgent',
  stat: 'priorityStat',
};

/** Look up one requisition label for the active handout language. */
export function orderPrintText(language: Locale, key: OrderPrintMessageKey): string {
  return orderPrintMessages[language][key];
}
