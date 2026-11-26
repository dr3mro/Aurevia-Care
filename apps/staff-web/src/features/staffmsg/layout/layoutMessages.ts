/**
 * Bilingual labels for the shared messenger and mail layout wrapper (WEB-07).
 *
 * Layout chrome is feature-neutral: the same back action and pane names serve
 * conversations and mail folders. Both locale bundles keep identical keys so a
 * missing translation fails the parity test rather than rendering an empty
 * control. No clinical content or identifier belongs in these strings.
 */

import type { Locale } from '../../../i18n/messages';

/** Every visible label key of the message layout wrapper. */
export const layoutKeys = [
  'layout',
  'backToList',
  'listPane',
  'detailPane',
  'composerPane',
] as const;

/** One label key of the message layout wrapper. */
export type LayoutMessageKey = (typeof layoutKeys)[number];

/** English and Arabic labels with identical key sets. */
export const layoutMessages: Record<Locale, Record<LayoutMessageKey, string>> = {
  en: {
    layout: 'Messages and mail layout',
    backToList: 'Back to conversations',
    listPane: 'Conversations and folders',
    detailPane: 'Selected conversation',
    composerPane: 'Message composer',
  },
  ar: {
    layout: 'تخطيط الرسائل والبريد',
    backToList: 'العودة إلى المحادثات',
    listPane: 'المحادثات والمجلدات',
    detailPane: 'المحادثة المحددة',
    composerPane: 'محرر الرسالة',
  },
};

/** Look up one layout label in the active locale. */
export function layoutText(locale: Locale, key: LayoutMessageKey): string {
  return layoutMessages[locale][key];
}
