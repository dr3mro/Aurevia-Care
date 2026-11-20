/**
 * Application-level labels for the patient app shell (PAT-01, UX-01).
 *
 * The shell owns a small, parity-checked English/Arabic dictionary so the title
 * and the four navigation tabs can be verified without rendering native views.
 * Arabic is a right-to-left locale; the navigator applies the native direction
 * through the single `applyDirection` helper, and the tab order mirrors with it.
 */

/** Supported display locales for the patient application. */
export type Locale = 'en' | 'ar';

/** Every application-level label key. */
export const appMessageKeys = [
  'appTitle',
  'tabAppointments',
  'tabReports',
  'tabMessages',
  'tabAccount',
  'tabAppointmentsAccessibility',
  'tabReportsAccessibility',
  'tabMessagesAccessibility',
  'tabAccountAccessibility',
  'accountMenu',
  'accountEnrollment',
  'accountRecovery',
  'accountSupport',
  'accountLockout',
  'messagesAttachments',
  'messagesInstructions',
  'back',
] as const;

/** One application-level label key. */
export type AppMessageKey = (typeof appMessageKeys)[number];

/** Bilingual application labels with identical key sets. */
const labels: Record<Locale, Record<AppMessageKey, string>> = {
  en: {
    appTitle: 'Aurevia Care',
    tabAppointments: 'Appointments',
    tabReports: 'Reports',
    tabMessages: 'Messages',
    tabAccount: 'Account',
    tabAppointmentsAccessibility: 'Appointments tab',
    tabReportsAccessibility: 'Reports tab',
    tabMessagesAccessibility: 'Messages tab',
    tabAccountAccessibility: 'Account tab',
    accountMenu: 'Account menu',
    accountEnrollment: 'Enrollment',
    accountRecovery: 'Recovery',
    accountSupport: 'Support',
    accountLockout: 'Lockout',
    messagesAttachments: 'Review attachments',
    messagesInstructions: 'Instructions',
    back: 'Back',
  },
  ar: {
    appTitle: 'أوريفيا للرعاية',
    tabAppointments: 'المواعيد',
    tabReports: 'التقارير',
    tabMessages: 'الرسائل',
    tabAccount: 'الحساب',
    tabAppointmentsAccessibility: 'تبويب المواعيد',
    tabReportsAccessibility: 'تبويب التقارير',
    tabMessagesAccessibility: 'تبويب الرسائل',
    tabAccountAccessibility: 'تبويب الحساب',
    accountMenu: 'قائمة الحساب',
    accountEnrollment: 'التسجيل',
    accountRecovery: 'استعادة الوصول',
    accountSupport: 'الدعم',
    accountLockout: 'القفل',
    messagesAttachments: 'مراجعة المرفقات',
    messagesInstructions: 'التعليمات',
    back: 'رجوع',
  },
};

/**
 * Look up one application label for the active locale.
 *
 * @param locale active display locale
 * @param key application label key
 * @returns the localized label for that locale
 */
export function appText(locale: Locale, key: AppMessageKey): string {
  return labels[locale][key];
}
