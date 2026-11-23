import type { Locale } from '../../../i18n/messages';
import { formatUnreadCount } from './domain';

/**
 * Bilingual labels for the notification settings slice (UX-01, WEB-09).
 *
 * The shared bundle is outside this feature's ownership, so the feature keeps a
 * small parity-checked en/ar dictionary. Counts use two explicit plural forms:
 * a single message must not read "1 messages", and Arabic must not borrow an
 * English plural rule. The full six-category Arabic ICU set is a localization
 * decision owned by the site; this slice only needs the singular/plural split.
 */
export const settingsKeys = [
  'title',
  'syntheticNotice',
  'muteLabel',
  'quietHoursTitle',
  'quietStart',
  'quietEnd',
  'quietActive',
  'quietInactive',
  'outOfOfficeLabel',
  'replyLabel',
  'delegateLabel',
  'delegateRequired',
  'soundLabel',
  'save',
  'saving',
  'saved',
  'unreadBadgeOne',
  'unreadBadgeOther',
  'workingCopy',
  'copyNotice',
  'compareTitle',
  'yourValues',
  'storedValues',
] as const;

/** One label key shared by both notification settings bundles. */
export type SettingsMessageKey = (typeof settingsKeys)[number];

/** English and Arabic labels with identical key sets. */
export const settingsMessages: Record<Locale, Record<SettingsMessageKey, string>> = {
  en: {
    title: 'Notification settings',
    syntheticNotice: 'Synthetic preferences only. No server setting is changed from this screen.',
    muteLabel: 'Mute all notifications',
    quietHoursTitle: 'Do not disturb hours',
    quietStart: 'Start hour (0-23)',
    quietEnd: 'End hour (0-23)',
    quietActive: 'Quiet hours active',
    quietInactive: 'Outside quiet hours',
    outOfOfficeLabel: 'Out of office',
    replyLabel: 'Automatic reply',
    delegateLabel: 'Delegate subject (required when out of office)',
    delegateRequired: 'Enter a delegate before saving an out-of-office reply.',
    soundLabel: 'Play notification sounds (off unless you opt in)',
    save: 'Save settings',
    saving: 'Saving settings…',
    saved: 'Settings saved',
    unreadBadgeOne: '{count} unread message',
    unreadBadgeOther: '{count} unread messages',
    workingCopy: 'Your working copy',
    copyNotice: 'A copy of your typed changes is kept here while the record is stale.',
    compareTitle: 'Compare changes',
    yourValues: 'Your values',
    storedValues: 'Stored values',
  },
  ar: {
    title: 'إعدادات الإشعارات',
    syntheticNotice: 'تفضيلات توضيحية فقط؛ لا يتم تغيير أي إعداد خادم من هذه الشاشة.',
    muteLabel: 'كتم كل الإشعارات',
    quietHoursTitle: 'ساعات عدم الإزعاج',
    quietStart: 'ساعة البداية (0-23)',
    quietEnd: 'ساعة النهاية (0-23)',
    quietActive: 'ساعات عدم الإزعاج مفعّلة',
    quietInactive: 'خارج ساعات عدم الإزعاج',
    outOfOfficeLabel: 'خارج المكتب',
    replyLabel: 'الرد التلقائي',
    delegateLabel: 'معرّف المفوّض (مطلوب عند التواجد خارج المكتب)',
    delegateRequired: 'أدخل مفوّضًا قبل حفظ الرد التلقائي لحالة خارج المكتب.',
    soundLabel: 'تشغيل أصوات الإشعارات (معطّلة ما لم تفعّلها)',
    save: 'حفظ الإعدادات',
    saving: 'جارٍ حفظ الإعدادات…',
    saved: 'تم حفظ الإعدادات',
    unreadBadgeOne: '{count} رسالة غير مقروءة',
    unreadBadgeOther: '{count} رسائل غير مقروءة',
    workingCopy: 'نسختك العاملة',
    copyNotice: 'تم الاحتفاظ بنسخة من تغييراتك هنا أثناء كون السجل قديمًا.',
    compareTitle: 'مقارنة التغييرات',
    yourValues: 'قيمك',
    storedValues: 'القيم المخزّنة',
  },
};

/** Look up one notification settings label for the active locale. */
export function settingsText(locale: Locale, key: SettingsMessageKey): string {
  return settingsMessages[locale][key];
}

/**
 * Build the accessible name of the unread badge for the active locale.
 *
 * @param locale active interface locale
 * @param count raw unread count, used only to choose the plural form
 * @returns the translated label with the bounded count substituted
 */
export function unreadBadgeLabel(locale: Locale, count: number): string {
  const key = count === 1 ? 'unreadBadgeOne' : 'unreadBadgeOther';
  return settingsText(locale, key).replace('{count}', formatUnreadCount(count));
}
