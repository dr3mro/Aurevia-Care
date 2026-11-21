import type { Locale } from '../../../i18n/messages';
import type { ChartPrintSection, LeftOutReason } from './domain';

/**
 * Chart-print-only labels. Shared bundles are outside this story's scope, so
 * the feature owns a small, parity-checked en/ar dictionary (UX-01). Machine
 * identifiers and clinical text stay in their source script and are never
 * translated here.
 */
export const chartPrintMessageKeys = [
  'chartPrint',
  'reportPrint',
  'preview',
  'close',
  'print',
  'scope',
  'visit',
  'dateRange',
  'from',
  'to',
  'visitId',
  'noVisit',
  'sections',
  'sectionProblems',
  'sectionAllergies',
  'sectionMedications',
  'sectionVitals',
  'sectionNotes',
  'sectionResults',
  'included',
  'leftOut',
  'leftOutEmpty',
  'reasonUnsigned',
  'reasonWithheld',
  'provenance',
  'notPrintable',
  'syntheticNotice',
  'patient',
] as const;

/** A label key used by the chart-print components. */
export type ChartPrintMessageKey = (typeof chartPrintMessageKeys)[number];

/** English and Arabic chart-print labels with identical key sets. */
export const chartPrintMessages: Record<Locale, Record<ChartPrintMessageKey, string>> = {
  en: {
    chartPrint: 'Print chart summary',
    reportPrint: 'Print signed report',
    preview: 'Print preview',
    close: 'Close',
    print: 'Print',
    scope: 'Print scope',
    visit: 'Visit',
    dateRange: 'Date range',
    from: 'From',
    to: 'To',
    visitId: 'Visit ID',
    noVisit: 'No visits are available.',
    sections: 'Sections to include',
    sectionProblems: 'Problems',
    sectionAllergies: 'Allergies',
    sectionMedications: 'Medications',
    sectionVitals: 'Vitals',
    sectionNotes: 'Signed notes',
    sectionResults: 'Released results',
    included: 'Included in this print',
    leftOut: 'Left out of this print',
    leftOutEmpty: 'Nothing was left out.',
    reasonUnsigned: 'Unsigned draft — only signed content is printed.',
    reasonWithheld: 'Withheld result — not released to the patient.',
    provenance: 'Provenance',
    notPrintable: 'This draft cannot be printed. Only signed reports print.',
    syntheticNotice: 'Synthetic fixture only. No real patient or report is used.',
    patient: 'Patient',
  },
  ar: {
    chartPrint: 'طباعة ملخص الملف',
    reportPrint: 'طباعة التقرير الموقّع',
    preview: 'معاينة الطباعة',
    close: 'إغلاق',
    print: 'طباعة',
    scope: 'نطاق الطباعة',
    visit: 'الزيارة',
    dateRange: 'مدى التاريخ',
    from: 'من',
    to: 'إلى',
    visitId: 'معرّف الزيارة',
    noVisit: 'لا توجد زيارات متاحة.',
    sections: 'الأقسام المضمنة',
    sectionProblems: 'المشكلات',
    sectionAllergies: 'الحساسيات',
    sectionMedications: 'الأدوية',
    sectionVitals: 'القياسات الحيوية',
    sectionNotes: 'الملاحظات الموقّعة',
    sectionResults: 'النتائج المُفرج عنها',
    included: 'مضمن في هذه الطباعة',
    leftOut: 'مستبعد من هذه الطباعة',
    leftOutEmpty: 'لم يُستبعد شيء.',
    reasonUnsigned: 'مسودة غير موقّعة؛ تُطبع المحتويات الموقّعة فقط.',
    reasonWithheld: 'نتيجة محجوبة؛ لم يتم الإفراج عنها للمريض.',
    provenance: 'بيانات المصدر',
    notPrintable: 'لا يمكن طباعة هذه المسودة؛ تُطبع التقارير الموقّعة فقط.',
    syntheticNotice: 'بيانات توضيحية فقط؛ لا يتم استخدام مريض أو تقرير حقيقي.',
    patient: 'المريض',
  },
};

/** Map each coded section to its bilingual label key. */
export const chartSectionMessageKey: Record<ChartPrintSection, ChartPrintMessageKey> = {
  problems: 'sectionProblems',
  allergies: 'sectionAllergies',
  medications: 'sectionMedications',
  vitals: 'sectionVitals',
  notes: 'sectionNotes',
  results: 'sectionResults',
};

/** Map each coded omission reason to a worded bilingual label key. */
export const leftOutReasonKey: Record<LeftOutReason, ChartPrintMessageKey> = {
  unsigned: 'reasonUnsigned',
  withheld: 'reasonWithheld',
};

/** Look up one chart-print label for the active locale. */
export function chartPrintText(locale: Locale, key: ChartPrintMessageKey): string {
  return chartPrintMessages[locale][key];
}
