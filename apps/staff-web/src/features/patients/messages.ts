import type { Locale } from '../../i18n/messages';
import { fillMessagePlaceholders } from '../duplicates/messagePlaceholders';

/**
 * Patient registration and search labels. The feature owns a small parity-checked
 * en/ar dictionary because the shared i18n bundle is outside this story's scope.
 */
export const patientsKeys = [
  'patients',
  'syntheticNotice',
  'searchLabel',
  'search',
  'searchTooShort',
  'resultsCaption',
  'colName',
  'colMrn',
  'colIssuer',
  'colDob',
  'register',
  'registerTitle',
  'registerIntro',
  'registerSubmit',
  'cancel',
  'fieldDisplayName',
  'fieldMrn',
  'fieldIssuer',
  'fieldDob',
  'fieldDobReason',
  'fieldLanguage',
  'registered',
  'errorSummary',
  'errorDisplayName',
  'errorMrn',
  'errorIssuer',
  'errorDob',
  'errorDobExclusive',
  'errorDobReason',
  'errorLanguage',
  'reasonNotKnown',
  'reasonNotDisclosed',
  'reasonUnableToCollect',
  'reasonUnknown',
  'overviewTitle',
  'identity',
  'patientId',
  'version',
  'mrn',
  'issuer',
  'dob',
  'language',
  'duplicateWarningTitle',
  'duplicateWarningDetail',
  'noDuplicates',
  'chartLink',
] as const;

/** A single patient-feature label key. */
export type PatientsMessageKey = (typeof patientsKeys)[number];

/** English and Arabic patient labels with identical key sets. */
export const patientMessages: Record<Locale, Record<PatientsMessageKey, string>> = {
  en: {
    patients: 'Patients',
    syntheticNotice:
      'Synthetic data only. Patients are read from and written to the connected API.',
    searchLabel: 'Search by name',
    search: 'Search',
    searchTooShort: 'Enter at least two characters.',
    resultsCaption: 'Patient search results',
    colName: 'Name',
    colMrn: 'MRN',
    colIssuer: 'Issuer',
    colDob: 'Date of birth',
    register: 'Register',
    registerTitle: 'Register patient',
    registerIntro:
      'Enter the original spelling and issuer-scoped identifiers. The API remains authoritative.',
    registerSubmit: 'Save registration',
    cancel: 'Cancel',
    fieldDisplayName: 'Display name',
    fieldMrn: 'MRN',
    fieldIssuer: 'MRN issuer',
    fieldDob: 'Date of birth (YYYY-MM-DD)',
    fieldDobReason: 'Unknown-date reason (code)',
    fieldLanguage: 'Language code',
    registered: 'Registered {name}',
    errorSummary: 'Fix the following fields',
    errorDisplayName: "Enter a name of 2-120 letters, spaces or ' . -.",
    errorMrn: 'Enter an MRN of 2-64 letters, digits or _ . : -.',
    errorIssuer: 'Enter an issuer of 2-32 letters, digits or _ . : -.',
    errorDob: 'Enter an ISO date of birth or a coded unknown reason.',
    errorDobExclusive: 'Enter a date of birth or an unknown reason, not both.',
    errorDobReason: 'Use a lowercase coded reason of 2-32 letters or underscores.',
    errorLanguage: 'Use a supported language code: en or ar.',
    reasonNotKnown: 'Not known',
    reasonNotDisclosed: 'Not disclosed',
    reasonUnableToCollect: 'Unable to collect',
    reasonUnknown: 'Reason not recorded',
    overviewTitle: 'Patient overview',
    identity: 'Identity',
    patientId: 'Patient ID',
    version: 'Version',
    mrn: 'MRN',
    issuer: 'Issuer',
    dob: 'Date of birth',
    language: 'Language',
    duplicateWarningTitle: 'Likely duplicate records',
    duplicateWarningDetail:
      'These records may belong to the same patient. Review them before linking; nothing is merged here.',
    noDuplicates: 'No likely duplicate records.',
    chartLink: 'Open chart',
  },
  ar: {
    patients: 'المرضى',
    syntheticNotice: 'بيانات توضيحية فقط؛ تُقرأ المرضى من الخدمة المتصلة ويُكتب إليها.',
    searchLabel: 'ابحث بالاسم',
    search: 'بحث',
    searchTooShort: 'أدخل حرفين على الأقل.',
    resultsCaption: 'نتائج البحث عن المرضى',
    colName: 'الاسم',
    colMrn: 'رقم الملف',
    colIssuer: 'الجهة المُصدرة',
    colDob: 'تاريخ الميلاد',
    register: 'تسجيل',
    registerTitle: 'تسجيل مريض',
    registerIntro:
      'أدخل التهجئة الأصلية والمعرّفات المرتبطة بالجهة المُصدرة. الخدمة هي المرجع النهائي.',
    registerSubmit: 'حفظ التسجيل',
    cancel: 'إلغاء',
    fieldDisplayName: 'الاسم المعروض',
    fieldMrn: 'رقم الملف',
    fieldIssuer: 'جهة إصدار رقم الملف',
    fieldDob: 'تاريخ الميلاد (YYYY-MM-DD)',
    fieldDobReason: 'سبب تاريخ الميلاد غير المعروف (رمز)',
    fieldLanguage: 'رمز اللغة',
    registered: 'تم تسجيل {name}',
    errorSummary: 'صحّح الحقول التالية',
    errorDisplayName: "أدخل اسمًا من حرفين إلى ١٢٠ حرفًا أو مسافة أو ' . -.",
    errorMrn: 'أدخل رقم ملف من ٢ إلى ٦٤ حرفًا أو رقمًا أو _ . : -.',
    errorIssuer: 'أدخل جهة إصدار من ٢ إلى ٣٢ حرفًا أو رقمًا أو _ . : -.',
    errorDob: 'أدخل تاريخ ميلاد بصيغة ISO أو رمز سبب عدم المعرفة.',
    errorDobExclusive: 'أدخل تاريخ ميلاد أو سبب عدم المعرفة، وليس كلاهما.',
    errorDobReason: 'استخدم رمز سبب صغيرًا من ٢ إلى ٣٢ حرفًا أو شرطة سفلية.',
    errorLanguage: 'استخدم رمز لغة مدعومًا: en أو ar.',
    reasonNotKnown: 'غير معروف',
    reasonNotDisclosed: 'لم يُفصح عنه',
    reasonUnableToCollect: 'تعذّر الجمع',
    reasonUnknown: 'لم يُسجّل السبب',
    overviewTitle: 'نظرة عامة على المريض',
    identity: 'الهوية',
    patientId: 'معرّف المريض',
    version: 'الإصدار',
    mrn: 'رقم الملف',
    issuer: 'الجهة المُصدرة',
    dob: 'تاريخ الميلاد',
    language: 'اللغة',
    duplicateWarningTitle: 'سجلات مرجّح تكرارها',
    duplicateWarningDetail:
      'قد تنتمي هذه السجلات إلى المريض نفسه. راجعها قبل الربط؛ لا يتم دمج أي شيء هنا.',
    noDuplicates: 'لا توجد سجلات مرجّح تكرارها.',
    chartLink: 'فتح الملف الطبي',
  },
};

/** Coded unknown-date reasons to their bilingual message keys. */
const DOB_REASON_MESSAGE_KEY: Readonly<Record<string, PatientsMessageKey>> = {
  not_known: 'reasonNotKnown',
  not_disclosed: 'reasonNotDisclosed',
  unable_to_collect: 'reasonUnableToCollect',
};

/** Resolve a coded unknown-date reason, falling back for a missing or unknown code. */
export function dobReasonKey(code: string | null | undefined): PatientsMessageKey {
  return DOB_REASON_MESSAGE_KEY[code ?? ''] ?? 'reasonUnknown';
}

/** Look up one patient label for the active locale. */
export function patientsText(locale: Locale, key: PatientsMessageKey): string {
  return patientMessages[locale][key];
}

/**
 * Fill named placeholders in a patient message. Unknown placeholders stay
 * visible so a missing value is never silently blank.
 */
export function patientsMessage(
  locale: Locale,
  key: PatientsMessageKey,
  values: Readonly<Record<string, string>> = {},
): string {
  return fillMessagePlaceholders(patientsText(locale, key), values);
}
