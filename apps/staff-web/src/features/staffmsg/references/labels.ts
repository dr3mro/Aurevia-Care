import type { Locale } from '../../../i18n/messages';

/**
 * Label keys owned by the staff-message reference feature. The shared i18n
 * bundle is outside this feature's scope, so the feature owns a small,
 * parity-checked en/ar dictionary. The four warning keys are the fixed reason
 * codes themselves, so a warning never needs the subject text to be rendered.
 */
export const referenceKeys = [
  'blocked',
  'blockedDetail',
  'subjectLabel',
  'insert',
  'UUID_LIKE',
  'DIGIT_RUN',
  'EMAIL_LIKE',
  'MRN_LIKE',
] as const;

/** One reference-feature label key, including the fixed warning codes. */
export type ReferenceMessageKey = (typeof referenceKeys)[number];

/**
 * Bilingual reference labels with identical key sets. Every value is fixed
 * copy: no patient, subject or target data is ever interpolated here.
 */
const referenceMessages: Record<Locale, Record<ReferenceMessageKey, string>> = {
  en: {
    blocked: 'Blocked',
    blockedDetail: 'You do not have access to this record.',
    subjectLabel: 'Subject',
    insert: 'Insert',
    UUID_LIKE: 'The subject may contain a UUID-like identifier.',
    DIGIT_RUN: 'The subject may contain a long number.',
    EMAIL_LIKE: 'The subject may contain an email address.',
    MRN_LIKE: 'The subject may contain a record identifier.',
  },
  ar: {
    blocked: 'محجوب',
    blockedDetail: 'لا تملك صلاحية الوصول إلى هذا السجل.',
    subjectLabel: 'الموضوع',
    insert: 'إدراج',
    UUID_LIKE: 'قد يحتوي الموضوع على معرّف يشبه UUID.',
    DIGIT_RUN: 'قد يحتوي الموضوع على رقم طويل.',
    EMAIL_LIKE: 'قد يحتوي الموضوع على عنوان بريد إلكتروني.',
    MRN_LIKE: 'قد يحتوي الموضوع على معرّف سجل.',
  },
};

/** Look up one reference-feature label for the active interface locale. */
export function referenceText(locale: Locale, key: ReferenceMessageKey): string {
  return referenceMessages[locale][key];
}
