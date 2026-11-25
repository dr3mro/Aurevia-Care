/**
 * Bilingual labels for the shared messenger and mail layout (WEB-09).
 *
 * The layout is owned by this feature folder, so it keeps a small parity-checked
 * dictionary instead of editing the shared bundle. Labels name regions and the
 * compact back action only; no clinical content, identifier or file name is
 * embedded. Both locales have the same key set so a locale switch cannot leave
 * an untranslated control behind.
 */

import type { Locale } from '../../../i18n/messages';

/** Every visible layout label key. */
export type LayoutMessageKey = 'back' | 'listPane' | 'detailPane' | 'auxiliaryPane' | 'composer' | 'panes';

/** Bilingual layout labels with identical key sets. */
export const layoutMessages: Record<Locale, Record<LayoutMessageKey, string>> = {
  en: {
    back: 'Back to list',
    listPane: 'List',
    detailPane: 'Detail',
    auxiliaryPane: 'Folders',
    composer: 'Compose',
    panes: 'Message panes',
  },
  ar: {
    back: 'العودة إلى القائمة',
    listPane: 'القائمة',
    detailPane: 'التفاصيل',
    auxiliaryPane: 'المجلدات',
    composer: 'الإنشاء',
    panes: 'لوحات الرسائل',
  },
};

/**
 * Resolve one layout label for the active locale.
 *
 * @param locale - Locale selected by the shell.
 * @param key - Region or action whose label is required.
 * @returns The translated label; the type system guarantees the key exists in both locales.
 */
export function layoutText(locale: Locale, key: LayoutMessageKey): string {
  return layoutMessages[locale][key];
}
