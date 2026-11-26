import type { Locale } from '../../../i18n/messages';

/**
 * Bilingual accessible names for the staff-message layout panes. The shared
 * message bundle is outside this slice's ownership, so this feature keeps a
 * small parity-checked dictionary. No clinical content or identifier is
 * embedded in a label.
 */
export const layoutKeys = ['back', 'listPane', 'detailPane', 'composerPane'] as const;

/** One staff-message layout label key. */
export type LayoutMessageKey = (typeof layoutKeys)[number];

/** Bilingual staff-message layout labels with identical key sets. */
export const layoutMessages: Record<Locale, Record<LayoutMessageKey, string>> = {
  en: {
    back: 'Back to list',
    listPane: 'Conversations and folders',
    detailPane: 'Selected conversation or message',
    composerPane: 'Message composer',
  },
  ar: {
    back: 'العودة إلى القائمة',
    listPane: 'المحادثات والمجلدات',
    detailPane: 'المحادثة أو الرسالة المحددة',
    composerPane: 'محرّر الرسالة',
  },
};

/** Look up one staff-message layout label for the active locale. */
export function layoutText(locale: Locale, key: LayoutMessageKey): string {
  return layoutMessages[locale][key];
}
