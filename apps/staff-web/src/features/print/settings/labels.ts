import type { Locale } from '../../../i18n/messages';
import { fillMessagePlaceholders } from '../../duplicates/messagePlaceholders';
import type { MessageValues } from '../../duplicates/messagePlaceholders';

/**
 * Print-profile-only label keys. The shared bundles are outside this feature's
 * scope, so the settings screen owns a small parity-checked en/ar dictionary.
 */
export const settingsKeys = [
  'title',
  'intro',
  'syntheticNotice',
  'editor',
  'facilityName',
  'address',
  'logoReference',
  'footerText',
  'defaultLanguage',
  'paperSize',
  'effectiveFrom',
  'version',
  'history',
  'noHistory',
  'currentProfile',
  'noEffectiveProfile',
  'approved',
  'pending',
  'approver',
  'propose',
  'approve',
  'proposalSaved',
  'approvedNotice',
  'errorSummary',
  'facilityNameRequired',
  'addressRequired',
  'logoNotApproved',
  'footerTextRequired',
  'invalidEffectiveDate',
  'languageEn',
  'languageAr',
  'paperA4',
  'paperLetter',
  'syntheticApprover',
] as const;

/** A label key used by the print profile settings screen. */
export type SettingsMessageKey = (typeof settingsKeys)[number];

/** English and Arabic print settings labels with identical key sets. */
export const settingsMessages: Record<Locale, Record<SettingsMessageKey, string>> = {
  en: {
    title: 'Print profile settings',
    intro:
      'Approved, effective-dated letterhead and footer settings, kept with their full history.',
    syntheticNotice: 'Synthetic data for demonstration only.',
    editor: 'Edit print profile',
    facilityName: 'Facility name',
    address: 'Facility address',
    logoReference: 'Approved logo reference',
    footerText: 'Footer text',
    defaultLanguage: 'Default handout language',
    paperSize: 'Default paper size',
    effectiveFrom: 'Effective from (YYYY-MM-DD)',
    version: 'Version',
    history: 'Version history',
    noHistory: 'No saved versions yet.',
    currentProfile: 'Current effective profile',
    noEffectiveProfile: 'No effective profile is in force yet.',
    approved: 'Approved',
    pending: 'Pending',
    approver: 'Approved by',
    propose: 'Propose version',
    approve: 'Approve',
    proposalSaved: 'Proposed print profile version {version}.',
    approvedNotice: 'Approved version {version}.',
    errorSummary: 'Correct the following fields',
    facilityNameRequired: 'Facility name is required.',
    addressRequired: 'Facility address is required.',
    logoNotApproved: 'Choose an approved logo asset.',
    footerTextRequired: 'Footer text is required.',
    invalidEffectiveDate: 'Enter a real YYYY-MM-DD date.',
    languageEn: 'English',
    languageAr: 'Arabic',
    paperA4: 'A4',
    paperLetter: 'Letter',
    syntheticApprover: 'Synthetic administrator',
  },
  ar: {
    title: 'إعدادات طباعة المنشآت',
    intro: 'إعدادات معتمدة وسارية بتاريخ للترويسة والتذييل، مع سجل كامل بالتغييرات.',
    syntheticNotice: 'بيانات تركيبية للعرض فقط.',
    editor: 'تحرير ملف الطباعة',
    facilityName: 'اسم المنشأة',
    address: 'عنوان المنشأة',
    logoReference: 'مرجع الشعار المعتمد',
    footerText: 'نص التذييل',
    defaultLanguage: 'لغة المطبوع الافتراضية',
    paperSize: 'حجم الورق الافتراضي',
    effectiveFrom: 'سريان من (YYYY-MM-DD)',
    version: 'الإصدار',
    history: 'سجل الإصدارات',
    noHistory: 'لا توجد إصدارات محفوظة بعد.',
    currentProfile: 'الملف الساري حالياً',
    noEffectiveProfile: 'لا يوجد ملف ساري حالياً.',
    approved: 'معتمد',
    pending: 'قيد الانتظار',
    approver: 'اعتمد بواسطة',
    propose: 'اقتراح إصدار',
    approve: 'اعتماد',
    proposalSaved: 'تم اقتراح إصدار ملف الطباعة {version}.',
    approvedNotice: 'تم اعتماد الإصدار {version}.',
    errorSummary: 'يرجى تصحيح الحقول التالية',
    facilityNameRequired: 'اسم المنشأة مطلوب.',
    addressRequired: 'عنوان المنشأة مطلوب.',
    logoNotApproved: 'اختر شعاراً من الأصول المعتمدة.',
    footerTextRequired: 'نص التذييل مطلوب.',
    invalidEffectiveDate: 'أدخل تاريخاً صحيحاً بصيغة YYYY-MM-DD.',
    languageEn: 'الإنجليزية',
    languageAr: 'العربية',
    paperA4: 'مقاس A4',
    paperLetter: 'مقاس خطابي',
    syntheticApprover: 'مسؤول تركيبي',
  },
};

/** Look up one print settings label for the active locale. */
export function settingsText(locale: Locale, key: SettingsMessageKey): string {
  return settingsMessages[locale][key];
}

/** Look up a print settings message and fill its named placeholders. */
export function settingsMessage(
  locale: Locale,
  key: SettingsMessageKey,
  values: MessageValues = {},
): string {
  return fillMessagePlaceholders(settingsText(locale, key), values);
}
