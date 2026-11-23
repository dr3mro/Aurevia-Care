import type { Locale } from '../../i18n/messages';
import type { ClinicalReviewState, DifferingField, ResultsFilter, ResultKind } from './types';

/**
 * Results-only labels and helpers.
 *
 * The file is deliberately not named `messages.ts`: the repository-wide message
 * parity registry discovers that filename and this feature's scope cannot
 * register a new bundle there. `resultsMessages.test.ts` enforces parity here.
 * Every visible string used by the results screen lives in these two bundles.
 */

/** Every visible label key used by the results feature. */
export const resultsKeys = [
  'title',
  'subtitle',
  'filterGroup',
  'syntheticNotice',
  'filterMine',
  'filterCoverage',
  'filterUnmatched',
  'criticalTitle',
  'criticalCount',
  'escalationPolicy',
  'criticalIconLabel',
  'dueLabel',
  'onTime',
  'overdue',
  'dueNow',
  'overdueIconLabel',
  'eventVerified',
  'eventAlertSent',
  'eventAttempt',
  'eventEscalated',
  'ownerLabel',
  'coverageLabel',
  'noOwner',
  'noFindings',
  'actionReadBack',
  'actionAcknowledge',
  'actionEscalate',
  'readBackRecipientRequired',
  'inboxTitle',
  'reviewSeparate',
  'colPatient',
  'colReport',
  'colSource',
  'colKind',
  'colReview',
  'colOwner',
  'kindFinal',
  'kindCorrected',
  'kindCritical',
  'reviewUnreviewed',
  'reviewAcknowledged',
  'reviewActionPending',
  'reviewResolved',
  'emptyRows',
  'reconTitle',
  'reconDetail',
  'reportSays',
  'closestOrder',
  'dobLabel',
  'differingName',
  'differingDateOfBirth',
  'differingAccession',
  'matched',
  'actionAskLab',
  'actionAssignOwner',
  'actionLink',
] as const;

/** One results label key. */
export type ResultsMessageKey = (typeof resultsKeys)[number];

/** English and Arabic labels with identical key sets. */
export const resultsMessages: Record<Locale, Record<ResultsMessageKey, string>> = {
  en: {
    title: 'Results',
    subtitle: 'Every final, corrected and critical result has a named owner and a coverage queue.',
    filterGroup: 'Filter results',
    syntheticNotice: 'Synthetic data only. No results service is connected.',
    filterMine: 'Mine',
    filterCoverage: 'My coverage queue',
    filterUnmatched: 'Unmatched',
    criticalTitle: 'Critical findings',
    criticalCount: '{count} need action',
    escalationPolicy: 'Escalation policy: owner, then backup queue, then medical director',
    criticalIconLabel: 'Critical result',
    dueLabel: 'Due',
    onTime: 'On time',
    overdue: 'Overdue by {minutes} min',
    dueNow: 'Due now',
    overdueIconLabel: 'Overdue',
    eventVerified: 'Verified by lab, routed to owner and coverage queue',
    eventAlertSent: 'Alert sent to the responsible clinician',
    eventAttempt: 'Phone attempt to patient: no answer',
    eventEscalated: 'Deadline passed, escalated to the backup contact',
    ownerLabel: 'Owner',
    coverageLabel: 'Coverage',
    noOwner: 'No named owner staffed',
    noFindings: 'No critical findings need action.',
    actionReadBack: 'Record contact and read-back',
    actionAcknowledge: 'Acknowledge',
    actionEscalate: 'Escalate now',
    readBackRecipientRequired: 'A recipient is required before a read-back can be recorded.',
    inboxTitle: 'Inbox',
    reviewSeparate: 'Clinical review state is separate from delivery state',
    colPatient: 'Patient and MRN',
    colReport: 'Report and date',
    colSource: 'Source',
    colKind: 'Kind',
    colReview: 'Review',
    colOwner: 'Owner',
    kindFinal: 'Final',
    kindCorrected: 'Corrected',
    kindCritical: 'Critical',
    reviewUnreviewed: 'Unreviewed',
    reviewAcknowledged: 'Acknowledged',
    reviewActionPending: 'Action pending',
    reviewResolved: 'Resolved',
    emptyRows: 'Nothing to show in this view.',
    reconTitle: 'Reconciliation: unmatched report',
    reconDetail:
      'A report waits here until patient, source, accession, destination and order identifiers match.',
    reportSays: 'Report says',
    closestOrder: 'Closest order',
    dobLabel: 'DOB',
    differingName: 'Name differs. Do not link until the lab confirms.',
    differingDateOfBirth: 'Date of birth differs. Do not link until the lab confirms.',
    differingAccession: 'Accession differs. Do not link until the lab confirms.',
    matched: 'Identifiers match. Linking is allowed.',
    actionAskLab: 'Ask the lab',
    actionAssignOwner: 'Assign owner',
    actionLink: 'Link report to order',
  },
  ar: {
    title: 'النتائج',
    subtitle: 'لكل نتيجة نهائية أو نتيجة مصححة أو نتيجة حرجة مالك محدد وقائمة تغطية.',
    filterGroup: 'تصفية النتائج',
    syntheticNotice: 'بيانات توضيحية فقط؛ لا توجد خدمة نتائج متصلة.',
    filterMine: 'خاص بي',
    filterCoverage: 'قائمة تغطيتي',
    filterUnmatched: 'غير مطابقة',
    criticalTitle: 'النتائج الحرجة',
    criticalCount: '{count} تحتاج إجراءً',
    escalationPolicy: 'سياسة التصعيد: المالك، ثم قائمة التغطية الاحتياطية، ثم المدير الطبي',
    criticalIconLabel: 'نتيجة حرجة',
    dueLabel: 'موعد التسليم',
    onTime: 'في الموعد',
    overdue: 'متأخرة بـ {minutes} دقيقة',
    dueNow: 'الموعد الآن',
    overdueIconLabel: 'متأخرة',
    eventVerified: 'تم التحقق من المختبر وإرسالها إلى المالك وقائمة التغطية',
    eventAlertSent: 'أُرسل تنبيه إلى الطبيب المسؤول',
    eventAttempt: 'محاولة اتصال هاتفي بالمريض: لا إجابة',
    eventEscalated: 'انتهى الموعد، تم التصعيد إلى جهة الاتصال الاحتياطية',
    ownerLabel: 'المالك',
    coverageLabel: 'التغطية',
    noOwner: 'لا يوجد مالك محدد',
    noFindings: 'لا توجد نتائج حرجة تحتاج إجراءً.',
    actionReadBack: 'تسجيل الاتصال والإعادة اللفظية',
    actionAcknowledge: 'إقرار الاستلام',
    actionEscalate: 'تصعيد فوري',
    readBackRecipientRequired: 'يلزم تحديد المستلم قبل تسجيل الإعادة اللفظية.',
    inboxTitle: 'صندوق النتائج',
    reviewSeparate: 'حالة المراجعة السريرية مستقلة عن حالة التسليم',
    colPatient: 'المريض ورقم الملف',
    colReport: 'التقرير والتاريخ',
    colSource: 'المصدر',
    colKind: 'النوع',
    colReview: 'المراجعة',
    colOwner: 'المالك',
    kindFinal: 'نهائية',
    kindCorrected: 'مصححة',
    kindCritical: 'حرجة',
    reviewUnreviewed: 'لم تُراجع',
    reviewAcknowledged: 'تم الإقرار',
    reviewActionPending: 'إجراء قيد الانتظار',
    reviewResolved: 'تمت التسوية',
    emptyRows: 'لا توجد نتائج في هذا العرض.',
    reconTitle: 'التسوية: تقرير غير مطابق',
    reconDetail: 'ينتظر التقرير هنا حتى تتطابق هوية المريض والمصدر والرقم والجهة ورقم الطلب.',
    reportSays: 'يقول التقرير',
    closestOrder: 'أقرب طلب',
    dobLabel: 'تاريخ الميلاد',
    differingName: 'الاسم مختلف. لا تربط قبل تأكيد المختبر.',
    differingDateOfBirth: 'تاريخ الميلاد مختلف. لا تربط قبل تأكيد المختبر.',
    differingAccession: 'رقم الوصول مختلف. لا تربط قبل تأكيد المختبر.',
    matched: 'المعرّفات متطابقة. يمكن الربط.',
    actionAskLab: 'مراسلة المختبر',
    actionAssignOwner: 'إسناد مالك',
    actionLink: 'ربط التقرير بالطلب',
  },
};

/** Look up one results label for the active locale. */
export function resultsText(locale: Locale, key: ResultsMessageKey): string {
  return resultsMessages[locale][key];
}

/**
 * Fill named placeholders, leaving an unknown placeholder visible.
 *
 * A missing value must render as a visible token rather than an empty sentence:
 * a blank clinical label is more dangerous than an odd-looking one.
 */
export function resultsMessage(
  locale: Locale,
  key: ResultsMessageKey,
  values: Readonly<Record<string, string>> = {},
): string {
  return resultsText(locale, key).replace(/\{(\w+)\}/g, (whole, name: string) => values[name] ?? whole);
}

/** Render a wall-clock time with Latin digits in the facility time zone. */
export function formatClockTime(iso: string, timeZone: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale, {
    timeZone,
    timeStyle: 'short',
    numberingSystem: 'latn',
  }).format(new Date(iso));
}

/** Chip labels indexed by inbox filter. */
export const filterMessageKeys: Record<ResultsFilter, ResultsMessageKey> = {
  mine: 'filterMine',
  coverage: 'filterCoverage',
  unmatched: 'filterUnmatched',
};

/** Kind pill wording indexed by result kind. */
export const kindMessageKeys: Record<ResultKind, ResultsMessageKey> = {
  final: 'kindFinal',
  corrected: 'kindCorrected',
  critical: 'kindCritical',
};

/** A shape-distinct marker per kind so status never rests on colour alone. */
export const kindMarkers: Record<ResultKind, string> = {
  final: '●',
  corrected: '◆',
  critical: '▲',
};

/** Clinical review wording indexed by review state. */
export const reviewMessageKeys: Record<ClinicalReviewState, ResultsMessageKey> = {
  unreviewed: 'reviewUnreviewed',
  acknowledged: 'reviewAcknowledged',
  'action-pending': 'reviewActionPending',
  resolved: 'reviewResolved',
};

/** Reconciliation warning wording indexed by the differing identifier. */
export const differingMessageKeys: Record<DifferingField, ResultsMessageKey> = {
  name: 'differingName',
  dateOfBirth: 'differingDateOfBirth',
  accession: 'differingAccession',
  none: 'matched',
};
