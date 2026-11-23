import { messages } from './messages';
import type { Locale, MessageKey } from './messages';

/** Document text direction for a supported locale. */
export type ScriptDirection = 'ltr' | 'rtl';

/** A person name that may have a local-script form alongside the Latin form. */
export interface PersonName {
  latinName: string;
  localName?: string;
}

/**
 * A run of text with the metadata an isolation element needs. `dir: 'auto'`
 * lets the platform resolve the run's direction from its first strong
 * character instead of forcing the interface direction onto foreign text.
 */
export interface ScriptRun {
  text: string;
  lang: string;
  dir: 'auto';
}

/**
 * Message bundles indexed by locale. A runtime key may be absent from a
 * partial bundle even though the typed key set guards normal call sites.
 */
export type MessageBundles = Record<Locale, Record<string, string | undefined>>;

/**
 * Values are always rendered with Latin digits. Locale `ar` otherwise resolves
 * to Arabic-Indic digits, which would put a different numeral system beside a
 * Latin identifier on the same clinical screen (UX-01, design decision D4).
 */
const NUMERAL_SYSTEM = 'latn';

/** Resolve document direction; Arabic is right-to-left (UX-01). */
export function directionFor(locale: Locale): ScriptDirection {
  return locale === 'ar' ? 'rtl' : 'ltr';
}

/** Resolve the display form of a name together with the language of its script. */
function nameRun(name: PersonName, locale: Locale): { text: string; lang: string } {
  const localName = locale === 'ar' ? name.localName : undefined;
  return localName !== undefined && localName !== ''
    ? { text: localName, lang: 'ar' }
    : { text: name.latinName, lang: 'en' };
}

/**
 * Prefer the local-script name in Arabic while always retaining a readable
 * Latin fallback for synthetic or partially translated records.
 */
export function displayLocalName(name: PersonName, locale: Locale): string {
  return nameRun(name, locale).text;
}

/** Present a name for an isolated, language-correct clinical display run. */
export function annotatedName(name: PersonName, locale: Locale): ScriptRun {
  const run = nameRun(name, locale);
  return { text: run.text, lang: run.lang, dir: 'auto' };
}

/** Annotate mixed-script clinical free text for isolated rendering. */
export function mixedScriptRun(text: string, lang: string): ScriptRun {
  return { text, lang, dir: 'auto' };
}

/** Format a UTC instant in an IANA time zone; Intl applies DST transitions. */
export function formatInstant(iso: string, timeZone: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale, {
    timeZone,
    dateStyle: 'medium',
    timeStyle: 'short',
    numberingSystem: NUMERAL_SYSTEM,
  }).format(new Date(iso));
}

/** Format a civil YYYY-MM-DD date at UTC midnight so the day never shifts. */
export function formatCivilDate(civilDate: string, locale: Locale): string {
  return new Intl.DateTimeFormat(locale, {
    timeZone: 'UTC',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    numberingSystem: NUMERAL_SYSTEM,
  }).format(new Date(`${civilDate}T00:00:00Z`));
}

/** Preserve the caller's unit verbatim while localizing the numeric value. */
export function formatMeasurement(value: number, unit: string, locale: Locale): string {
  const number = new Intl.NumberFormat(locale, {
    maximumFractionDigits: 3,
    numberingSystem: NUMERAL_SYSTEM,
  }).format(value);
  return `${number} ${unit}`;
}

/** Attach language and direction metadata to a mixed-script clinical string. */
export function scriptAnnotation(
  text: string,
  lang: string,
  direction: ScriptDirection,
): { text: string; lang: string; dir: ScriptDirection } {
  return { text, lang, dir: direction };
}

/**
 * Resolve a message by key, falling back to English when the active locale's
 * translation is missing. A key unknown to both bundles resolves to itself so
 * a partial bundle never renders an empty label.
 */
export function resolveMessage(locale: Locale, key: string, bundles: MessageBundles): string {
  const translated = bundles[locale][key];
  const english = bundles.en[key];
  return translated ?? english ?? key;
}

/** Look up a translated message for the active locale. */
export function message(locale: Locale, key: MessageKey): string {
  return resolveMessage(locale, key, messages);
}
