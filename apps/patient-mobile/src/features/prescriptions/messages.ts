/**
 * Bilingual patient-facing copy for released prescriptions (PAT-02, UX-01).
 *
 * The four status words never claim the medicine is "ready": only dispensing
 * evidence yields "Handed over". Both bundles carry the identical key set so
 * parity can be asserted without rendering native views.
 */
import type { PrescriptionLocale, PrescriptionStatus } from './types';

/** Screen-level copy for one locale. */
export interface PrescriptionCopy {
  readonly title: string;
  readonly subtitle: string;
  readonly empty: string;
  readonly error: string;
  readonly reference: string;
}

/** Screen copy with identical keys for English and Arabic. */
export const PRESCRIPTION_COPY: Record<PrescriptionLocale, PrescriptionCopy> = {
  en: {
    title: 'Prescriptions',
    subtitle: 'Updates from your pharmacy',
    empty: 'No released prescriptions',
    error: 'Unable to load prescriptions',
    reference: 'Reference',
  },
  ar: {
    title: 'الوصفات الطبية',
    subtitle: 'تحديثات من صيدليتك',
    empty: 'لا توجد وصفات مُصدرّة',
    error: 'تعذّر تحميل الوصفات',
    reference: 'المرجع',
  },
};

/** Plain status word per status and locale (RX-02). */
export const STATUS_WORDS: Record<PrescriptionStatus, Record<PrescriptionLocale, string>> = {
  sent: { en: 'Sent to pharmacy', ar: 'أُرسلت إلى الصيدلية' },
  accepted: { en: 'Pharmacy accepted', ar: 'قبلتها الصيدلية' },
  needsCheck: { en: 'Pharmacy needs to check with your doctor', ar: 'تحتاج الصيدلية إلى مراجعة طبيبك' },
  handedOver: { en: 'Handed over', ar: 'تم تسليم الدواء' },
};
