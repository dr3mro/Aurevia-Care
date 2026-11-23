import type { Locale } from '../../i18n/messages';
import type { QueueAction } from './types';

/**
 * Operations-only bilingual labels (ADM-02, WEB-09). The shared i18n bundle is
 * outside this story's scope, so the feature owns a small parity-checked
 * dictionary. Queue ids and note ids are valid keys, which keeps the screen free
 * of per-row label switches.
 */
export const operationsKeys = [
  'title',
  'subtitle',
  'queueCardLabel',
  'coverageRegion',
  'coverageTitle',
  'coverageExplanation',
  'assignBackup',
  'manageQueue',
  'toneUrgent',
  'toneWaiting',
  'toneCalm',
  'actionLabel',
  'actionAssign',
  'actionResolve',
  'actionRetry',
  'actionEscalate',
  'actionDialogTitle',
  'reasonLabel',
  'reasonRequired',
  'recordAction',
  'retryCommand',
  'cancel',
  'recordedStatus',
  'compareNotice',
  'copyNotice',
  'noteOldest',
  'noteWaitingLab',
  'noteOverdue',
  'noteNeedsReason',
  'noteScanFailed',
  'noteSunday',
  'noteReviewLinks',
  'unsigned-encounters',
  'unreconciled-reports',
  'unacknowledged-critical-findings',
  'rejected-exchanges',
  'document-failures',
  'missing-coverage',
  'revoked-accounts',
] as const;

/** One operations label key. */
export type OperationsMessageKey = (typeof operationsKeys)[number];

/** Bilingual operations labels with identical key sets. */
export const operationsMessages: Record<Locale, Record<OperationsMessageKey, string>> = {
  en: {
    title: 'Operations queues',
    subtitle: 'Assign, resolve, retry or escalate. Every action records a reason.',
    queueCardLabel: 'Operational queue counts',
    coverageRegion: 'Missing coverage',
    coverageTitle: 'Missing coverage: {unit}',
    coverageExplanation:
      'No staffed backup for {window}. Results for this unit cannot be silently reassigned to an unstaffed queue.',
    assignBackup: 'Assign backup',
    manageQueue: 'Manage {queue}',
    toneUrgent: 'Urgent',
    toneWaiting: 'Waiting',
    toneCalm: 'Calm',
    actionLabel: 'Action',
    actionAssign: 'Assign',
    actionResolve: 'Resolve',
    actionRetry: 'Retry',
    actionEscalate: 'Escalate',
    actionDialogTitle: 'Take action on {queue}',
    reasonLabel: 'Reason (required)',
    reasonRequired: 'Enter a reason before taking this action.',
    recordAction: 'Record action',
    retryCommand: 'Retry command',
    cancel: 'Cancel',
    recordedStatus: 'Action recorded: {action} on {queue}',
    compareNotice: 'Working copy kept: {reason}',
    copyNotice: 'Draft copied: {reason}',
    noteOldest: 'Oldest: 2 days',
    noteWaitingLab: '2 waiting for the lab',
    noteOverdue: '1 overdue',
    noteNeedsReason: 'Replay needs a reason',
    noteScanFailed: 'Scan or checksum failed',
    noteSunday: 'Imaging desk, Sunday',
    noteReviewLinks: 'Review open links',
    'unsigned-encounters': 'Unsigned encounters',
    'unreconciled-reports': 'Unreconciled reports',
    'unacknowledged-critical-findings': 'Unacknowledged critical findings',
    'rejected-exchanges': 'Rejected exchanges',
    'document-failures': 'Document failures',
    'missing-coverage': 'Missing coverage',
    'revoked-accounts': 'Revoked accounts',
  },
  ar: {
    title: 'قوائم العمليات',
    subtitle: 'إسناد أو حل أو إعادة محاولة أو تصعيد. كل إجراء يُسجَّب سببه.',
    queueCardLabel: 'أعداد قوائم العمليات',
    coverageRegion: 'تغطية مفقودة',
    coverageTitle: 'تغطية مفقودة: {unit}',
    coverageExplanation:
      'لا يوجد موظف احتياطي خلال {window}. لا يمكن إسناد نتائج هذه الوحدة بصمت إلى قائمة غير مأهولة.',
    assignBackup: 'إسناد موظف احتياطي',
    manageQueue: 'إدارة {queue}',
    toneUrgent: 'عاجل',
    toneWaiting: 'بانتظار',
    toneCalm: 'هادئ',
    actionLabel: 'الإجراء',
    actionAssign: 'إسناد',
    actionResolve: 'حل',
    actionRetry: 'إعادة محاولة',
    actionEscalate: 'تصعيد',
    actionDialogTitle: 'اتخاذ إجراء بشأن {queue}',
    reasonLabel: 'السبب (مطلوب)',
    reasonRequired: 'أدخل سببًا قبل اتخاذ هذا الإجراء.',
    recordAction: 'تسجيل الإجراء',
    retryCommand: 'إعادة محاولة الإجراء',
    cancel: 'إلغاء',
    recordedStatus: 'تم تسجيل الإجراء: {action} على {queue}',
    compareNotice: 'تم الاحتفاظ بالنسخة العاملة: {reason}',
    copyNotice: 'تم نسخ المسودة: {reason}',
    noteOldest: 'الأقدم: يومان',
    noteWaitingLab: 'اثنان بانتظار المختبر',
    noteOverdue: 'واحد متأخر',
    noteNeedsReason: 'إعادة المحاولة تحتاج سببًا',
    noteScanFailed: 'فحص أو تحقق فاشل',
    noteSunday: 'مكتب التصوير، الأحد',
    noteReviewLinks: 'مراجعة الروابط المفتوحة',
    'unsigned-encounters': 'زيارات غير موقّعة',
    'unreconciled-reports': 'تقارير غير مطابقة',
    'unacknowledged-critical-findings': 'نتائج حرجة غير مُقرّ بها',
    'rejected-exchanges': 'تبادلات مرفوضة',
    'document-failures': 'فشل المستندات',
    'missing-coverage': 'تغطية مفقودة',
    'revoked-accounts': 'حسابات مُلغاة',
  },
};

/** Look up one operations label for the active locale. */
export function operationsText(locale: Locale, key: OperationsMessageKey): string {
  return operationsMessages[locale][key];
}

/** The bilingual word key for each reason-bearing queue action. */
export const queueActionKey: Record<QueueAction, OperationsMessageKey> = {
  assign: 'actionAssign',
  resolve: 'actionResolve',
  retry: 'actionRetry',
  escalate: 'actionEscalate',
};
