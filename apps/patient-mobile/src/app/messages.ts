/**
 * Application-level labels for the patient app shell (PAT-01, UX-01).
 *
 * The shell owns a small, parity-checked English/Arabic dictionary so the title
 * can be verified without the navigator. Arabic is a right-to-left locale; the
 * navigator passes the active locale through to the text run.
 */

/** Supported display locales for the patient application. */
export type Locale = 'en' | 'ar';

/** Every application-level label key. */
export const appMessageKeys = ['appTitle'] as const;

/** One application-level label key. */
export type AppMessageKey = (typeof appMessageKeys)[number];

/** Bilingual application labels with identical key sets. */
const labels: Record<Locale, Record<AppMessageKey, string>> = {
  en: {
    appTitle: 'Aurevia Care',
  },
  ar: {
    appTitle: 'أوريفيا للرعاية',
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
