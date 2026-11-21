import type { Locale } from '../../i18n/messages';

/**
 * Text for the fixed system pages (WEB-03, WEB-07). The shared bundle is owned
 * by another module, so this feature keeps its own parity-checked en/ar map.
 * The two state views reused from `components/StateViews` own their own labels.
 */
export const systemKeys = [
  'signedOutTitle',
  'signedOutDetail',
  'notFoundTitle',
  'notFoundDetail',
  'unsupportedTitle',
  'unsupportedDetail',
  'signIn',
  'goHome',
] as const;

/** A label owned by a system page. */
export type SystemMessageKey = (typeof systemKeys)[number];

/** English and Arabic system labels with identical key sets. */
export const systemMessages: Record<Locale, Record<SystemMessageKey, string>> = {
  en: {
    signedOutTitle: 'You have signed out',
    signedOutDetail: 'Your session has ended. Sign in again to continue.',
    notFoundTitle: 'Page not found',
    notFoundDetail: 'The page you requested does not exist. Go home to continue.',
    unsupportedTitle: 'Unsupported browser or screen size',
    unsupportedDetail:
      'This portal needs a supported desktop browser at least 768 pixels wide. No patient information is shown here.',
    signIn: 'Sign in',
    goHome: 'Go home',
  },
  ar: {
    signedOutTitle: 'لقد سجّلت الخروج',
    signedOutDetail: 'انتهت جلستك. سجّل الدخول مرة أخرى للمتابعة.',
    notFoundTitle: 'الصفحة غير موجودة',
    notFoundDetail: 'الصفحة التي طلبتها غير موجودة. عد إلى الصفحة الرئيسية للمتابعة.',
    unsupportedTitle: 'متصفح أو حجم شاشة غير مدعوم',
    unsupportedDetail:
      'تتطلب هذه البوابة متصفحاً مكتبياً مدعوماً بعرض 768 بكسل على الأقل. لا يتم عرض أي معلومات عن المرضى هنا.',
    signIn: 'تسجيل الدخول',
    goHome: 'الصفحة الرئيسية',
  },
};

/** Look up one system label for the active locale (UX-01). */
export function systemText(locale: Locale, key: SystemMessageKey): string {
  return systemMessages[locale][key];
}
