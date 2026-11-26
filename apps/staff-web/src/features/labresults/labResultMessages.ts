import { formatCivilDate } from '../../i18n/format';
import type { Locale } from '../../i18n/messages';
import { fillMessagePlaceholders } from '../duplicates/messagePlaceholders';
import type { ReportStatus, ResultFlag } from './domain';

/**
 * English and Arabic labels for laboratory result entry and verification.
 *
 * The file is deliberately not named `messages.ts`: the repository-wide message
 * parity registry discovers that name and is owned outside this feature's scope.
 * This bundle enforces its own key, placeholder and script parity in
 * `labResultMessages.test.ts`.
 */

/** Every label key used by the result entry screen, in both locales. */
export const labResultKeys = [
  'title',
  'syntheticNotice',
  'reportRegion',
  'statusPreliminary',
  'statusFinal',
  'gridCaption',
  'colTest',
  'colValue',
  'colUnit',
  'colRange',
  'colFlag',
  'colPrevious',
  'colChange',
  'valueInputLabel',
  'flagNormal',
  'flagLow',
  'flagHigh',
  'flagCritical',
  'noPrevious',
  'changeLarge',
  'changeNone',
  'changeNoPrevious',
  'verifyRegion',
  'enteredBy',
  'verifyAs',
  'summaryTitle',
  'summaryCount',
  'summaryItem',
  'notifyStepTitle',
  'notifyStepDetail',
  'verifyButton',
  'sameUserBlocked',
  'verifiedStatus',
  'noResults',
] as const;

/** One result-entry label key. */
export type LabResultMessageKey = (typeof labResultKeys)[number];

/** English and Arabic labels with identical key sets. */
export const labResultMessages: Record<Locale, Record<LabResultMessageKey, string>> = {
  en: {
    title: 'Laboratory result entry and verification',
    syntheticNotice: 'Synthetic data only. No patient record or analyser is connected.',
    reportRegion: 'Result entry for accession {accession}',
    statusPreliminary: 'Preliminary report',
    statusFinal: 'Final report',
    gridCaption: 'Observations with units, reference ranges and abnormal flags',
    colTest: 'Test',
    colValue: 'Value',
    colUnit: 'Unit',
    colRange: 'Reference range',
    colFlag: 'Flag',
    colPrevious: 'Previous result',
    colChange: 'Change check',
    valueInputLabel: 'Result value for {test}',
    flagNormal: 'Normal',
    flagLow: 'Low',
    flagHigh: 'High',
    flagCritical: 'Critical',
    noPrevious: 'No previous result',
    changeLarge: 'Large change from previous: {percent}%',
    changeNone: 'Within change limit',
    changeNoPrevious: 'No previous value',
    verifyRegion: 'Verification and release summary',
    enteredBy: 'Entered by {subject}',
    verifyAs: 'Verifying as {subject}',
    summaryTitle: 'What will be released',
    summaryCount: '{count} observations will be released',
    summaryItem: '{test}: {value}',
    notifyStepTitle: 'Critical result notification step',
    notifyStepDetail: 'Notify the responsible clinician and record read-back before release.',
    verifyButton: 'Verify and prepare release',
    sameUserBlocked: 'The enterer cannot verify this report. A second user must verify.',
    verifiedStatus: 'Verified by {subject}. The summary is ready for release.',
    noResults: 'This report has no observations to enter or verify.',
  },
  ar: {
    title: 'إدخال نتائج المختبر والتحقق منها',
    syntheticNotice: 'بيانات توضيحية فقط؛ لا يوجد سجل مريض أو جهاز تحليل متصل.',
    reportRegion: 'إدخال النتائج للوصول {accession}',
    statusPreliminary: 'تقرير أولي',
    statusFinal: 'تقرير نهائي',
    gridCaption: 'الملاحظات مع الوحدات والنطاقات المرجعية وعلامات الشذوذ',
    colTest: 'الاختبار',
    colValue: 'القيمة',
    colUnit: 'الوحدة',
    colRange: 'النطاق المرجعي',
    colFlag: 'العلامة',
    colPrevious: 'النتيجة السابقة',
    colChange: 'فحص التغيّر',
    valueInputLabel: 'قيمة نتيجة {test}',
    flagNormal: 'طبيعي',
    flagLow: 'منخفض',
    flagHigh: 'مرتفع',
    flagCritical: 'حرج',
    noPrevious: 'لا توجد نتيجة سابقة',
    changeLarge: 'تغيّر كبير عن السابق: {percent}%',
    changeNone: 'ضمن حد التغيّر',
    changeNoPrevious: 'لا توجد قيمة سابقة',
    verifyRegion: 'التحقق وملخص الإصدار',
    enteredBy: 'أدخلها {subject}',
    verifyAs: 'التحقق بصفة {subject}',
    summaryTitle: 'ما الذي سيتم إصداره',
    summaryCount: 'سيتم إصدار {count} من الملاحظات',
    summaryItem: 'النتيجة {test}: {value}',
    notifyStepTitle: 'خطوة إشعار النتيجة الحرجة',
    notifyStepDetail: 'أبلغ الطبيب المسؤول وسجّل القراءة المتبادلة قبل الإصدار.',
    verifyButton: 'تحقق وجهّز الإصدار',
    sameUserBlocked: 'لا يمكن لمن أدخل النتيجة أن يتحقق منها؛ يجب أن يتحقق مستخدم ثانٍ.',
    verifiedStatus: 'تم التحقق بواسطة {subject}. الملخص جاهز للإصدار.',
    noResults: 'لا توجد ملاحظات لإدخالها أو التحقق منها في هذا التقرير.',
  },
};

/** Look up one result-entry label for the active locale. */
export function labText(locale: Locale, key: LabResultMessageKey): string {
  return labResultMessages[locale][key];
}

/** Fill named placeholders, leaving unknown ones visible rather than blank. */
export function labMessage(
  locale: Locale,
  key: LabResultMessageKey,
  values: Readonly<Record<string, string>> = {},
): string {
  return fillMessagePlaceholders(labText(locale, key), values);
}

/** Abnormal flag label keys, indexed by flag. */
const FLAG_KEYS: Readonly<Record<ResultFlag, LabResultMessageKey>> = {
  normal: 'flagNormal',
  low: 'flagLow',
  high: 'flagHigh',
  critical: 'flagCritical',
};

/** Resolve the word for an abnormal flag. */
export function flagKey(flag: ResultFlag): LabResultMessageKey {
  return FLAG_KEYS[flag];
}

/** Report status label keys, indexed by status. */
const STATUS_KEYS: Readonly<Record<ReportStatus, LabResultMessageKey>> = {
  preliminary: 'statusPreliminary',
  final: 'statusFinal',
};

/** Resolve the word for a report status. */
export function statusKey(status: ReportStatus): LabResultMessageKey {
  return STATUS_KEYS[status];
}

/** Format a number with Latin digits in both locales (design decision D4). */
export function formatNumber(value: number, locale: Locale): string {
  return new Intl.NumberFormat(locale, {
    maximumFractionDigits: 3,
    numberingSystem: 'latn',
  }).format(value);
}

/** Keep the value and its invariant unit in one non-breaking-space run. */
export function formatValue(value: number, unit: string, locale: Locale): string {
  return `${formatNumber(value, locale)}\u00a0${unit}`;
}

/** Format the absolute delta percentage with one fractional digit in both locales. */
export function formatPercent(value: number, locale: Locale): string {
  return new Intl.NumberFormat(locale, {
    maximumFractionDigits: 1,
    numberingSystem: 'latn',
  }).format(value);
}

/** Format the civil date of a previous result at UTC midnight. */
export function formatPreviousDate(civilDate: string, locale: Locale): string {
  return formatCivilDate(civilDate, locale);
}
