/**
 * Plain-word English and Arabic labels for the patient tracker (PAT-02, UX-01).
 *
 * A patient must never read an internal workflow state ("collected_or_scheduled"),
 * so every canonical step has a short, item-appropriate phrase in both locales.
 * The two bundles have identical key sets; the parity test fails the build when
 * a key is added to one language only.
 */
import type { TrackerItemKind, TrackerLocale, TrackerStepKey } from './types';

/** Message key for one step of one item kind, e.g. `lab_order.collected_or_scheduled`. */
export type StepMessageKey = `${TrackerItemKind}.${TrackerStepKey}`;

/** Message key for the visible heading of one item kind. */
export type ItemTitleMessageKey = `${TrackerItemKind}.title`;

/** Screen-level message keys. */
export type UiMessageKey = 'title' | 'subtitle' | 'empty';

/** Every tracker message key. */
export type TrackerMessageKey = StepMessageKey | ItemTitleMessageKey | UiMessageKey;

/** Flat list of every key, used by the translation-parity test. */
export const TRACKER_MESSAGE_KEYS: readonly TrackerMessageKey[] = [
  'title',
  'subtitle',
  'empty',
  'prescription.title',
  'lab_order.title',
  'imaging_order.title',
  'prescription.placed',
  'prescription.delivered',
  'prescription.accepted',
  'prescription.collected_or_scheduled',
  'prescription.resulted',
  'prescription.released',
  'prescription.acknowledged',
  'lab_order.placed',
  'lab_order.delivered',
  'lab_order.accepted',
  'lab_order.collected_or_scheduled',
  'lab_order.resulted',
  'lab_order.released',
  'lab_order.acknowledged',
  'imaging_order.placed',
  'imaging_order.delivered',
  'imaging_order.accepted',
  'imaging_order.collected_or_scheduled',
  'imaging_order.resulted',
  'imaging_order.released',
  'imaging_order.acknowledged',
];

/** Bilingual plain-word labels; no clinical values or identifiers are present. */
const MESSAGES: Record<TrackerLocale, Record<TrackerMessageKey, string>> = {
  en: {
    title: 'Where is it?',
    subtitle: 'Your tests, scans and prescriptions',
    empty: 'Nothing to track right now',
    'prescription.title': 'Prescription',
    'lab_order.title': 'Lab test',
    'imaging_order.title': 'Scan',
    'prescription.placed': 'Ordered',
    'prescription.delivered': 'Sent to the pharmacy',
    'prescription.accepted': 'Pharmacy accepted',
    'prescription.collected_or_scheduled': 'Ready for pickup',
    'prescription.resulted': 'Prescription filled',
    'prescription.released': 'Released to you',
    'prescription.acknowledged': 'You confirmed',
    'lab_order.placed': 'Ordered',
    'lab_order.delivered': 'Sent to the lab',
    'lab_order.accepted': 'Lab received it',
    'lab_order.collected_or_scheduled': 'Sample taken',
    'lab_order.resulted': 'Result ready for your doctor',
    'lab_order.released': 'Released to you',
    'lab_order.acknowledged': 'You confirmed',
    'imaging_order.placed': 'Ordered',
    'imaging_order.delivered': 'Sent to imaging',
    'imaging_order.accepted': 'Imaging received it',
    'imaging_order.collected_or_scheduled': 'Appointment scheduled',
    'imaging_order.resulted': 'Report ready for your doctor',
    'imaging_order.released': 'Released to you',
    'imaging_order.acknowledged': 'You confirmed',
  },
  ar: {
    title: 'أين هي؟',
    subtitle: 'فحوصاتك وصورك ووصفاتك',
    empty: 'لا يوجد شيء لتتبعه الآن',
    'prescription.title': 'الوصفة',
    'lab_order.title': 'فحص مختبر',
    'imaging_order.title': 'صورة شعاعية',
    'prescription.placed': 'تم الطلب',
    'prescription.delivered': 'أُرسلت إلى الصيدلية',
    'prescription.accepted': 'قبلتها الصيدلية',
    'prescription.collected_or_scheduled': 'جاهزة للاستلام',
    'prescription.resulted': 'تم صرف الوصفة',
    'prescription.released': 'أُتيحت لك',
    'prescription.acknowledged': 'أكدت الاستلام',
    'lab_order.placed': 'تم الطلب',
    'lab_order.delivered': 'أُرسل إلى المختبر',
    'lab_order.accepted': 'استلمه المختبر',
    'lab_order.collected_or_scheduled': 'تم أخذ العينة',
    'lab_order.resulted': 'النتيجة جاهزة لطبيبك',
    'lab_order.released': 'أُتيحت لك',
    'lab_order.acknowledged': 'أكدت الاستلام',
    'imaging_order.placed': 'تم الطلب',
    'imaging_order.delivered': 'أُرسل إلى التصوير',
    'imaging_order.accepted': 'استلمه قسم التصوير',
    'imaging_order.collected_or_scheduled': 'تم تحديد الموعد',
    'imaging_order.resulted': 'التقرير جاهز لطبيبك',
    'imaging_order.released': 'أُتيحت لك',
    'imaging_order.acknowledged': 'أكدت الاستلام',
  },
};

/**
 * Look up one plain-word label for the active locale.
 *
 * @param locale active display locale
 * @param key message key (a screen label or an `itemKind.step` pair)
 * @returns the translated plain words
 */
export function trackerText(locale: TrackerLocale, key: TrackerMessageKey): string {
  return MESSAGES[locale][key];
}
