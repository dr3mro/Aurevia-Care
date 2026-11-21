import type { Locale } from '../../../i18n/messages';

/** Language of the generated handout, independent of the interface locale. */
export type HandoutLanguage = 'en' | 'ar';

/** Why a handout is printed; recorded with the print audit by the owning screen. */
export type PrintReason = 'give-to-patient' | 'referral' | 'patient-request';

/** Why a handout cannot be produced, shown to the user in words. */
export type RefusalReason =
  | 'draft-source'
  | 'unsigned-source'
  | 'order-not-placed'
  | 'wrong-organization'
  | 'missing-print-permission';

/**
 * Print-preview-only label keys. The shared bundles are outside this feature's
 * scope, so the dialog owns a small, parity-checked en/ar dictionary.
 */
export const printMessageKeys = [
  'title',
  'close',
  'print',
  'download',
  'language',
  'languageEn',
  'languageAr',
  'reason',
  'reasonGiveToPatient',
  'reasonReferral',
  'reasonPatientRequest',
  'reasonRequired',
  'refusal',
  'refusalDetail',
  'refusalDraftSource',
  'refusalUnsignedSource',
  'refusalOrderNotPlaced',
  'refusalWrongOrganization',
  'refusalMissingPrintPermission',
] as const;

/** A label key used by the print preview dialog. */
export type PrintMessageKey = (typeof printMessageKeys)[number];

/** English and Arabic print-preview labels with identical key sets. */
export const printMessages: Record<Locale, Record<PrintMessageKey, string>> = {
  en: {
    title: 'Print preview',
    close: 'Close',
    print: 'Print',
    download: 'Download',
    language: 'Handout language',
    languageEn: 'English',
    languageAr: 'Arabic',
    reason: 'Why are you printing?',
    reasonGiveToPatient: 'Give to patient',
    reasonReferral: 'Referral',
    reasonPatientRequest: 'Patient request',
    reasonRequired: 'Choose a reason before printing',
    refusal: 'This handout cannot be printed',
    refusalDetail: 'The source record does not allow a patient handout.',
    refusalDraftSource: 'The source is still a draft.',
    refusalUnsignedSource: 'The source is not signed.',
    refusalOrderNotPlaced: 'The order has not been placed.',
    refusalWrongOrganization: 'The handout belongs to another organization.',
    refusalMissingPrintPermission: 'Your role has no permission to print this handout.',
  },
  ar: {
    title: 'معاينة الطباعة',
    close: 'إغلاق',
    print: 'طباعة',
    download: 'تنزيل',
    language: 'لغة المطبوع',
    languageEn: 'الإنجليزية',
    languageAr: 'العربية',
    reason: 'لماذا تطبع؟',
    reasonGiveToPatient: 'تسليم إلى المريض',
    reasonReferral: 'إحالة',
    reasonPatientRequest: 'طلب المريض',
    reasonRequired: 'اختر سببًا قبل الطباعة',
    refusal: 'لا يمكن طباعة هذا المطبوع',
    refusalDetail: 'لا يسمح السجل المصدر بمطبوع للمريض.',
    refusalDraftSource: 'المصدر لا يزال مسودة.',
    refusalUnsignedSource: 'المصدر غير موقّع.',
    refusalOrderNotPlaced: 'لم يتم تقديم الطلب.',
    refusalWrongOrganization: 'المطبوع يخص مؤسسة أخرى.',
    refusalMissingPrintPermission: 'لا يملك دورك صلاحية طباعة هذا المطبوع.',
  },
};

/** Map each coded print reason to its bilingual label key. */
export const printReasonKey: Record<PrintReason, PrintMessageKey> = {
  'give-to-patient': 'reasonGiveToPatient',
  referral: 'reasonReferral',
  'patient-request': 'reasonPatientRequest',
};

/** Map each coded refusal reason to a worded bilingual label key. */
export const refusalReasonKey: Record<RefusalReason, PrintMessageKey> = {
  'draft-source': 'refusalDraftSource',
  'unsigned-source': 'refusalUnsignedSource',
  'order-not-placed': 'refusalOrderNotPlaced',
  'wrong-organization': 'refusalWrongOrganization',
  'missing-print-permission': 'refusalMissingPrintPermission',
};

/** Look up one print-preview label for the active locale. */
export function printText(locale: Locale, key: PrintMessageKey): string {
  return printMessages[locale][key];
}
