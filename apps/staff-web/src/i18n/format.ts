import { messages } from './messages';
import type { Locale, MessageKey } from './messages';

/** Document text direction for a supported locale. */
export type ScriptDirection = 'ltr' | 'rtl';

/** A person name that may have a local-script form alongside the Latin form. */
export interface PersonName {
  latinName: string;
  localName?: string;
}

/** Resolve document direction; Arabic is right-to-left (UX-01). */
export function directionFor(locale: Locale): ScriptDirection {
  return locale === 'ar' ? 'rtl' : 'ltr';
}

/**
 * Prefer the local-script name in Arabic while always retaining a readable
 * Latin fallback for synthetic or partially translated records.
 */
export function displayLocalName(name: PersonName, locale: Locale): string {
  if (locale === 'ar' && name.localName) {
    return name.localName;
  }
  return name.latinName;
}

/** Format a UTC instant in an IANA time zone; Intl applies DST transitions. */
export function formatInstant(iso: string, timeZone: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale, {
    timeZone,
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(iso));
}

/** Format a civil YYYY-MM-DD date at UTC midnight so the day never shifts. */
export function formatCivilDate(civilDate: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale, {
    timeZone: 'UTC',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  }).format(new Date(`${civilDate}T00:00:00Z`));
}

/** Preserve the caller's unit verbatim while localizing the numeric value. */
export function formatMeasurement(value: number, unit: string, locale: Locale): string {
  return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 3 }).format(value)} ${unit}`;
}

/** Attach language and direction metadata to a mixed-script clinical string. */
export function scriptAnnotation(
  text: string,
  lang: string,
  direction: ScriptDirection,
): { text: string; lang: string; dir: ScriptDirection } {
  return { text, lang, dir: direction };
}

/** Look up a translated message for the active locale. */
export function message(locale: Locale, key: MessageKey): string {
  return messages[locale][key];
}
