import type { Locale } from '../i18n/messages';

/** Strings owned by the application shell and its placeholder routes. */
export type ShellTextKey = 'welcome' | 'notFound' | 'signOut' | 'userMenu' | 'notifications';

/**
 * English and Arabic shell labels with identical key sets. They live beside
 * the shell because the shared message bundle is owned by another module.
 */
export const shellLabels: Record<Locale, Record<ShellTextKey, string>> = {
  en: {
    welcome: 'Welcome',
    notFound: 'Page not found',
    signOut: 'Sign out',
    userMenu: 'User menu',
    notifications: 'Notifications',
  },
  ar: {
    welcome: 'مرحباً',
    notFound: 'الصفحة غير موجودة',
    signOut: 'تسجيل الخروج',
    userMenu: 'قائمة المستخدم',
    notifications: 'الإشعارات',
  },
};

/** Resolve a shell label for the active locale (UX-01). */
export function shellText(locale: Locale, key: ShellTextKey): string {
  return shellLabels[locale][key];
}
