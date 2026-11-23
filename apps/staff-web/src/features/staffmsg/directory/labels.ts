import type { Locale } from '../../../i18n/messages';
import {
  CROSS_FACILITY_MESSAGING_DISABLED,
  CROSS_ORGANIZATION_MESSAGING_DISABLED,
} from './domain';

/**
 * Directory labels. The shared bundle is outside this feature's ownership, so
 * the feature keeps a small parity-checked en/ar dictionary (UX-01). Refusals
 * are keyed by the machine code from the authoritative server rule, never by
 * free text, so a translated sentence cannot change the meaning of a denial.
 */
export const directoryKeys = [
  'title',
  'syntheticNotice',
  'search',
  'searchHint',
  'facilityLabel',
  'otherFacility',
  'empty',
  'blockedOrgReason',
  'blockedFacilityReason',
  'keyboardHint',
  'selected',
] as const;

/** One label key shared by both directory message bundles. */
export type DirectoryMessageKey = (typeof directoryKeys)[number];

/** Bilingual directory labels with identical key sets. */
export const directoryMessages: Record<Locale, Record<DirectoryMessageKey, string>> = {
  en: {
    title: 'Staff contact directory',
    syntheticNotice: 'Synthetic contacts only. No message is created from this list.',
    search: 'Search by name, role or unit',
    searchHint: 'Search in Arabic or English by name, role or unit.',
    facilityLabel: 'Facility',
    otherFacility: 'Another facility',
    empty: 'No contacts match this search.',
    blockedOrgReason: `Messaging this person is disabled: ${CROSS_ORGANIZATION_MESSAGING_DISABLED}.`,
    blockedFacilityReason: `Cross-facility messaging is not approved for this tenant: ${CROSS_FACILITY_MESSAGING_DISABLED}.`,
    keyboardHint: 'Use the arrow keys to move, Enter to select and Escape to clear.',
    selected: 'Selected recipient',
  },
  ar: {
    title: 'دليل جهات اتصال الموظفين',
    syntheticNotice: 'جهات اتصال توضيحية فقط؛ لا يتم إنشاء أي رسالة من هذه القائمة.',
    search: 'ابحث بالاسم أو الدور أو الوحدة',
    searchHint: 'ابحث بالاسم بالعربية أو الإنجليزية، وبالدور أو الوحدة بالإنجليزية.',
    facilityLabel: 'المرفق',
    otherFacility: 'منشأة أخرى',
    empty: 'لا توجد جهات اتصال تطابق هذا البحث.',
    blockedOrgReason: `المراسلة مع هذا الشخص غير مفعّلة: ${CROSS_ORGANIZATION_MESSAGING_DISABLED}.`,
    blockedFacilityReason: `المراسلة بين المنشآت غير معتمدة لهذا المستأجر: ${CROSS_FACILITY_MESSAGING_DISABLED}.`,
    keyboardHint: 'استخدم مفاتيح الأسهم للتنقل وEnter للاختيار وEscape للمسح.',
    selected: 'المستلم المختار',
  },
};

/** Look up one directory label for the active locale. */
export function directoryText(locale: Locale, key: DirectoryMessageKey): string {
  return directoryMessages[locale][key];
}

/** Choose the refusal label from a machine code, defaulting to the safer rule. */
export function refusalKeyForCode(code: string | undefined): DirectoryMessageKey {
  if (code === CROSS_FACILITY_MESSAGING_DISABLED) {
    return 'blockedFacilityReason';
  }
  return 'blockedOrgReason';
}
