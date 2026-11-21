import type { Locale } from '../../i18n/messages';
import type { ReportSectionKey } from './domain';

/**
 * Referrer-view label keys. The shared i18n bundle is outside this story's
 * scope, so the feature owns a small parity-checked en/ar dictionary.
 */
export const radreferrerKeys = [
  'radreferrer',
  'syntheticNotice',
  'signedBy',
  'version',
  'signedAt',
  'sectionTechnique',
  'sectionComparison',
  'sectionFindings',
  'sectionImpression',
  'sectionFollowUp',
  'addendumHeading',
  'addendumBody',
  'acknowledge',
  'acknowledgeVersion',
] as const;

/** One referrer-view label key. */
export type RadreferrerMessageKey = (typeof radreferrerKeys)[number];

/** Bilingual referrer-view labels with identical key sets. */
const radreferrerMessages: Record<Locale, Record<RadreferrerMessageKey, string>> = {
  en: {
    radreferrer: 'Imaging report',
    syntheticNotice: 'Synthetic data only. The signed report is read from the connected API.',
    signedBy: 'Signed by',
    version: 'Version',
    signedAt: 'Signed at (UTC ISO)',
    sectionTechnique: 'Technique',
    sectionComparison: 'Comparison',
    sectionFindings: 'Findings',
    sectionImpression: 'Impression',
    sectionFollowUp: 'Follow-up',
    addendumHeading: 'Unacknowledged addendum',
    addendumBody: 'Addendum version {version} is waiting for your acknowledgment.',
    acknowledge: 'Acknowledge',
    acknowledgeVersion: 'Acknowledge version {version}',
  },
  ar: {
    radreferrer: 'تقرير الأشعة',
    syntheticNotice: 'بيانات توضيحية فقط؛ يُقرأ التقرير الموقّع من الخدمة المتصلة.',
    signedBy: 'موقّع بواسطة',
    version: 'الإصدار',
    signedAt: 'وقت التوقيع (بتوقيت UTC)',
    sectionTechnique: 'التقنية',
    sectionComparison: 'المقارنة',
    sectionFindings: 'النتائج',
    sectionImpression: 'الانطباع',
    sectionFollowUp: 'المتابعة',
    addendumHeading: 'ملحق لم يُقرّ استلامه',
    addendumBody: 'الملحق بالإصدار {version} بانتظار إقرار استلامك.',
    acknowledge: 'إقرار الاستلام',
    acknowledgeVersion: 'إقرار استلام الإصدار {version}',
  },
};

/** Map each narrated section to its bilingual heading key. */
export const sectionMessageKey: Record<ReportSectionKey, RadreferrerMessageKey> = {
  technique: 'sectionTechnique',
  comparison: 'sectionComparison',
  findings: 'sectionFindings',
  impression: 'sectionImpression',
  followUp: 'sectionFollowUp',
};

/** Look up one referrer-view label for the active locale. */
export function radreferrerText(locale: Locale, key: RadreferrerMessageKey): string {
  return radreferrerMessages[locale][key];
}
