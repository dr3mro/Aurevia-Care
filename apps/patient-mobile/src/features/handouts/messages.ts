/**
 * Bilingual patient handout copy (PAT-02, UX-01).
 *
 * The shared `patientDesign` message table is owned outside this story, so the
 * handout-specific labels live here. Both bundles carry exactly the same keys;
 * a parity test in the acceptance suite keeps them in step. Only plain words
 * are stored here: no handout content, patient data or identifiers.
 */
import type { Locale } from '../appointments/patientDesign';

/** Every handout screen message key. */
export const HANDOUT_TEXT_KEYS = [
  'handoutsTitle',
  'handoutsSubtitle',
  'noHandouts',
  'loadError',
  'prescription',
  'orderSheet',
  'chartSummary',
  'releasedOn',
  'version',
  'corrected',
  'replacedBy',
  'view',
  'saveCopy',
  'share',
  'downloadCheck',
] as const;

/** One handout screen message key. */
export type HandoutTextKey = (typeof HANDOUT_TEXT_KEYS)[number];

/** Message keys used for the three handout kinds. */
export type HandoutKindTextKey = 'prescription' | 'orderSheet' | 'chartSummary';

/** Bilingual handout labels; no clinical values or identifiers are present. */
const HANDOUT_TEXT: Record<Locale, Record<HandoutTextKey, string>> = {
  en: {
    handoutsTitle: 'Handouts',
    handoutsSubtitle: 'Documents your clinician released to you',
    noHandouts: 'No handouts',
    loadError: 'Unable to load handouts',
    prescription: 'Prescription',
    orderSheet: 'Order sheet',
    chartSummary: 'Chart summary',
    releasedOn: 'Released',
    version: 'Version',
    corrected: 'Corrected',
    replacedBy: 'Replaced by version',
    view: 'View',
    saveCopy: 'Save a copy',
    share: 'Share',
    downloadCheck: 'Each copy is checked against your linked chart and the current release.',
  },
  ar: {
    handoutsTitle: 'المواد المُسلّمة',
    handoutsSubtitle: 'مستندات أتاحها لك طبيبك',
    noHandouts: 'لا توجد مواد',
    loadError: 'تعذّر تحميل المواد',
    prescription: 'وصفة طبية',
    orderSheet: 'طلب فحوصات',
    chartSummary: 'ملخص الملف',
    releasedOn: 'تاريخ الإتاحة',
    version: 'النسخة',
    corrected: 'مصححة',
    replacedBy: 'استُبدلت بالنسخة',
    view: 'عرض',
    saveCopy: 'حفظ نسخة',
    share: 'مشاركة',
    downloadCheck: 'يتحقق كل تنزيل من ارتباطك بملفك ومن سريان الإتاحة.',
  },
};

/**
 * Look up one handout label for the active locale.
 *
 * @param locale active display locale
 * @param key handout message key
 * @returns the translated plain-language string
 */
export function handoutText(locale: Locale, key: HandoutTextKey): string {
  return HANDOUT_TEXT[locale][key];
}
