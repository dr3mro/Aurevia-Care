/** English and Arabic labels for the prescription print button (UX-01, WEB-09). */

import type { Locale } from '../../../i18n/messages';
import type { PrescriptionPrintBlocker } from './types';

/** Label keys owned by this feature; the shared bundles are out of scope. */
export const prescriptionMessageKeys = [
  'print',
  'reprint',
  'reprintNote',
  'copyLegend',
  'patientCopy',
  'pharmacyCopy',
  'blockedDraft',
  'blockedNotPrintable',
  'blockedNotAttested',
  'blockedChannel',
  'recorded',
  'downloaded',
] as const;

/** One label key used by the prescription print button. */
export type PrescriptionMessageKey = (typeof prescriptionMessageKeys)[number];

/** Bilingual labels with identical key sets. */
export const prescriptionMessages: Record<Locale, Record<PrescriptionMessageKey, string>> = {
  en: {
    print: 'Print prescription',
    reprint: 'Reprint prescription',
    reprintNote: 'This is a reprint — the same handout version will be reused.',
    copyLegend: 'Choose the handout copy',
    patientCopy: 'Patient copy',
    pharmacyCopy: 'Pharmacy copy',
    blockedDraft: 'A draft prescription cannot be printed.',
    blockedNotPrintable: 'This prescription state cannot be printed.',
    blockedNotAttested: 'An unsigned prescription cannot be printed.',
    blockedChannel: 'This prescription has no approved print channel.',
    recorded: 'Print recorded.',
    downloaded: 'Download recorded.',
  },
  ar: {
    print: 'طباعة الوصفة',
    reprint: 'إعادة طباعة الوصفة',
    reprintNote: 'هذه إعادة طباعة — سيُعاد استخدام نفس نسخة المطبوع.',
    copyLegend: 'اختر نسخة المطبوع',
    patientCopy: 'نسخة المريض',
    pharmacyCopy: 'نسخة الصيدلية',
    blockedDraft: 'لا يمكن طباعة وصفة مسودة.',
    blockedNotPrintable: 'لا يمكن طباعة هذه الوصفة في حالتها الحالية.',
    blockedNotAttested: 'لا يمكن طباعة وصفة غير موقّعة.',
    blockedChannel: 'هذه الوصفة ليس لها قناة طباعة معتمدة.',
    recorded: 'تم تسجيل الطباعة.',
    downloaded: 'تم تسجيل التنزيل.',
  },
};

/** Map each eligibility blocker to its worded bilingual label. */
export const blockerKey: Record<PrescriptionPrintBlocker, PrescriptionMessageKey> = {
  draft: 'blockedDraft',
  'not-printable': 'blockedNotPrintable',
  'not-attested': 'blockedNotAttested',
  'channel-not-approved': 'blockedChannel',
};

/** Look up one prescription print label for the active locale. */
export function prescriptionText(locale: Locale, key: PrescriptionMessageKey): string {
  return prescriptionMessages[locale][key];
}
