/**
 * Bilingual labels for the staff message layout (WEB-07, UX-01).
 *
 * The shared message bundle is outside this slice, so the layout keeps a small
 * parity-checked dictionary. Every value is a control or region name; no
 * clinical content, identifier or file name is embedded in a label.
 */

import type { Locale } from '../../../i18n/messages';
import type { PaneName } from './panes';

/** Every visible layout label key. */
export const layoutKeys = [
  'layoutLabel',
  'back',
  'drawerToggle',
  'drawerClose',
  'scrimClose',
  'auxiliaryPane',
  'listPane',
  'detailPane',
  'composerPane',
] as const;

/** One layout label key. */
export type LayoutMessageKey = (typeof layoutKeys)[number];

/** English and Arabic layout labels with identical key sets. */
export const layoutMessages: Record<Locale, Record<LayoutMessageKey, string>> = {
  en: {
    layoutLabel: 'Messages layout',
    back: 'Back',
    drawerToggle: 'Show hidden panes',
    drawerClose: 'Close hidden panes',
    scrimClose: 'Dismiss the panes overlay',
    auxiliaryPane: 'Navigation',
    listPane: 'List',
    detailPane: 'Detail',
    composerPane: 'Compose',
  },
  ar: {
    layoutLabel: 'تخطيط الرسائل',
    back: 'رجوع',
    drawerToggle: 'إظهار اللوحات المخفية',
    drawerClose: 'إغلاق اللوحات المخفية',
    scrimClose: 'إغلاق طبقة اللوحات',
    auxiliaryPane: 'التنقل',
    listPane: 'القائمة',
    detailPane: 'التفاصيل',
    composerPane: 'كتابة',
  },
};

/** Accessible name key for each content pane. */
export const PANE_LABEL_KEYS: Record<PaneName, LayoutMessageKey> = {
  auxiliary: 'auxiliaryPane',
  list: 'listPane',
  detail: 'detailPane',
};

/** Look up one layout label for the active locale. */
export function layoutText(locale: Locale, key: LayoutMessageKey): string {
  return layoutMessages[locale][key];
}
