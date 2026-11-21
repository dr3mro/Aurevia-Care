import type { Locale } from '../../i18n/messages';
import type { QueueAction, QueueId } from './types';

/** Operational-queue label keys, with one English and one Arabic value each. */
export const operationsKeys = [
  'queuesTitle',
  'syntheticNotice',
  'count',
  'oldest',
  'notAvailable',
  'actions',
  'reason',
  'reasonRequired',
  'cancel',
  'confirm',
  'sent',
  'retryQueue',
  'colItem',
  'colDetail',
  'colState',
  'colActions',
  'actionAssign',
  'actionRetry',
  'actionEscalate',
  'actionReview',
  'tabWorkItems',
  'tabDeadLetters',
  'tabOverdueFollowUps',
  'tabDuplicateReviews',
  'tabClinicWorklist',
  'operatorHome',
  'openQueues',
  'statePendingReview',
  'stateReviewed',
  'stateUnassigned',
  'stateAssigned',
  'reasonNotRecorded',
] as const;

/** A label key owned by the operations feature. */
export type OperationsMessageKey = (typeof operationsKeys)[number];

/** Bilingual operational-queue labels with identical key sets (UX-01). */
const operationsMessages: Record<Locale, Record<OperationsMessageKey, string>> = {
  en: {
    queuesTitle: 'Operational queues',
    syntheticNotice: 'Synthetic data only. Queues are read from the connected API.',
    count: 'Items',
    oldest: 'Oldest item age:',
    notAvailable: 'Not available from this endpoint',
    actions: 'Actions',
    reason: 'Reason',
    reasonRequired: 'Enter a reason before sending this action.',
    cancel: 'Cancel',
    confirm: 'Send action',
    sent: 'Action sent once',
    retryQueue: 'Retry loading this queue',
    colItem: 'Item',
    colDetail: 'Detail',
    colState: 'State',
    colActions: 'Actions',
    actionAssign: 'Assign',
    actionRetry: 'Retry',
    actionEscalate: 'Escalate',
    actionReview: 'Review',
    tabWorkItems: 'Work items',
    tabDeadLetters: 'Dead letters',
    tabOverdueFollowUps: 'Overdue follow-ups',
    tabDuplicateReviews: 'Duplicate reviews',
    tabClinicWorklist: 'Clinic worklist',
    operatorHome: 'Operations today',
    openQueues: 'Open operational queues',
    statePendingReview: 'pending review',
    stateReviewed: 'reviewed',
    stateUnassigned: 'unassigned',
    stateAssigned: 'assigned',
    reasonNotRecorded: 'No reason recorded',
  },
  ar: {
    queuesTitle: 'قوائم العمليات',
    syntheticNotice: 'بيانات توضيحية فقط؛ تُقرأ القوائم من الخدمة المتصلة.',
    count: 'العناصر',
    oldest: 'عمر أقدم عنصر:',
    notAvailable: 'غير متوفر من هذه الخدمة',
    actions: 'إجراءات',
    reason: 'السبب',
    reasonRequired: 'أدخل سببًا قبل إرسال هذا الإجراء.',
    cancel: 'إلغاء',
    confirm: 'إرسال الإجراء',
    sent: 'تم إرسال الإجراء مرة واحدة',
    retryQueue: 'إعادة تحميل هذه القائمة',
    colItem: 'العنصر',
    colDetail: 'التفاصيل',
    colState: 'الحالة',
    colActions: 'الإجراءات',
    actionAssign: 'إسناد',
    actionRetry: 'إعادة محاولة',
    actionEscalate: 'تصعيد',
    actionReview: 'مراجعة',
    tabWorkItems: 'عناصر العمل',
    tabDeadLetters: 'الرسائل الميتة',
    tabOverdueFollowUps: 'المتابعات المتأخرة',
    tabDuplicateReviews: 'مراجعات التكرار',
    tabClinicWorklist: 'قائمة عمل العيادة',
    operatorHome: 'عمليات اليوم',
    openQueues: 'فتح قوائم العمليات',
    statePendingReview: 'بانتظار المراجعة',
    stateReviewed: 'تمت المراجعة',
    stateUnassigned: 'غير مسند',
    stateAssigned: 'مسند',
    reasonNotRecorded: 'لم يُسجَّل سبب',
  },
};

/** Map a queue to its translated tab label key. */
export const queueTabKey: Record<QueueId, OperationsMessageKey> = {
  'work-items': 'tabWorkItems',
  'dead-letters': 'tabDeadLetters',
  'overdue-follow-ups': 'tabOverdueFollowUps',
  'duplicate-reviews': 'tabDuplicateReviews',
  'clinic-worklist': 'tabClinicWorklist',
};

/** Map an action to its translated button label key. */
export const actionKey: Record<QueueAction, OperationsMessageKey> = {
  assign: 'actionAssign',
  retry: 'actionRetry',
  escalate: 'actionEscalate',
  review: 'actionReview',
};

/** Look up one operational-queue label for the active locale. */
export function queuesText(locale: Locale, key: OperationsMessageKey): string {
  return operationsMessages[locale][key];
}

/** Feature-owned state words that must not stay English in the Arabic UI. */
const stateMessageKeys: Record<string, OperationsMessageKey> = {
  'pending review': 'statePendingReview',
  reviewed: 'stateReviewed',
  unassigned: 'stateUnassigned',
  assigned: 'stateAssigned',
  'No reason recorded': 'reasonNotRecorded',
};

/**
 * Translate a feature-owned state or fallback phrase; server-owned text with
 * no local key is displayed verbatim, never machine-translated.
 */
export function displayQueueText(locale: Locale, text: string): string {
  const key = stateMessageKeys[text];
  return key === undefined ? text : queuesText(locale, key);
}
