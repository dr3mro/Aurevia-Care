/**
 * Fixed-precision money helpers for FIN-01 self-pay invoices.
 *
 * An amount is always an integer count of one currency's minor units; no
 * floating point value ever represents an amount. Intl supplies both the
 * currency's fraction-digit count and the localized presentation, while the
 * parser converts typed text directly into an integer.
 */

/**
 * Number of minor-unit fraction digits a currency uses, read from platform
 * locale data (for example 2 for USD, 0 for JPY, 3 for KWD).
 *
 * @param currency three-letter ISO 4217 code
 * @returns the fraction-digit count declared by the currency
 */
export function minorUnitDigits(currency: string): number {
  // Currency styles always resolve fraction digits; the optional type reflects
  // other Intl styles, not this one.
  return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
    .maximumFractionDigits!;
}

/**
 * Format an integer count of minor units as localized currency text. Latin
 * digits are requested explicitly so an Arabic locale never mixes numeral
 * systems with the opaque identifiers shown beside the amount (PRD-05).
 *
 * The division is only a display conversion for Intl; all amounts and all
 * arithmetic stay in integer minor units.
 *
 * @param minor integer amount in the currency's minor units
 * @param currency three-letter ISO 4217 code of the invoice
 * @param locale BCP 47 interface locale (`en` or `ar`)
 * @returns localized currency text with the correct fraction digits
 */
export function formatMoney(minor: number, currency: string, locale: string): string {
  const digits = minorUnitDigits(currency);
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    numberingSystem: 'latn',
  }).format(minor / 10 ** digits);
}

/** Machine-readable reason a typed money string was refused. */
export type MoneyParseError = 'empty' | 'invalid' | 'too-many-decimals';

/** Result of parsing typed money text without any floating point amount. */
export type MoneyParseResult =
  | { ok: true; minor: number }
  | { ok: false; error: MoneyParseError };

/**
 * Parse typed currency text into an integer count of minor units. The decimal
 * separator splits the whole and fraction runs, each is validated as digits,
 * then the two runs are concatenated and padded before one integer conversion.
 * More fraction digits than the currency allows are refused rather than rounded.
 *
 * @param text value typed by the user
 * @param currency three-letter ISO 4217 code of the invoice
 * @returns the integer minor amount or a stable refusal reason
 */
export function parseMoney(text: string, currency: string): MoneyParseResult {
  const trimmed = text.trim();
  if (trimmed === '') {
    return { ok: false, error: 'empty' };
  }
  const parts = trimmed.split('.');
  if (parts.length > 2) {
    return { ok: false, error: 'invalid' };
  }
  const whole = parts[0]!;
  const fraction = parts.length === 2 ? parts[1]! : '';
  if (!/^\d+$/.test(whole)) {
    return { ok: false, error: 'invalid' };
  }
  if (fraction !== '' && !/^\d+$/.test(fraction)) {
    return { ok: false, error: 'invalid' };
  }
  const digits = minorUnitDigits(currency);
  if (fraction.length > digits) {
    return { ok: false, error: 'too-many-decimals' };
  }
  const minor = Number(`${whole}${fraction.padEnd(digits, '0')}`);
  if (!Number.isSafeInteger(minor)) {
    return { ok: false, error: 'invalid' };
  }
  return { ok: true, minor };
}
