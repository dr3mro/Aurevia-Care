import { describe, expect, it } from 'vitest';
import {
  annotatedName,
  displayLocalName,
  formatCivilDate,
  formatInstant,
  formatMeasurement,
  message,
  mixedScriptRun,
  resolveMessage,
} from './format';
import type { MessageBundles, PersonName } from './format';
import { messages } from './messages';

// Synthetic examples only. Arabic-Indic and Extended Arabic-Indic digits must
// never appear in a value next to a Latin identifier (UX-01, design D4).
const NON_LATIN_DIGITS = /[\u0660-\u0669\u06F0-\u06F9]/;

describe('UX-01 numeric form is locale independent', () => {
  it('renders an instant with Latin digits in both locales', () => {
    const instant = '2026-06-15T12:00:00Z';
    for (const locale of ['en', 'ar'] as const) {
      const rendered = formatInstant(instant, 'Asia/Riyadh', locale);
      expect(rendered).toContain('15');
      expect(rendered).not.toMatch(NON_LATIN_DIGITS);
    }
  });

  it('renders a civil date with Latin digits and no calendar-day shift', () => {
    const en = formatCivilDate('2026-02-28', 'en');
    const ar = formatCivilDate('2026-02-28', 'ar');
    expect(en).toContain('28');
    expect(ar).toContain('28');
    expect(ar).not.toMatch(NON_LATIN_DIGITS);
  });

  it('renders grouped measurements with Latin digits in both locales', () => {
    const en = formatMeasurement(1234.5, 'mg', 'en');
    const ar = formatMeasurement(1234.5, 'mg', 'ar');
    expect(en).toBe('1,234.5 mg');
    expect(ar).toBe('1,234.5 mg');
    expect(ar).not.toMatch(NON_LATIN_DIGITS);
  });

  it('keeps a negative value and its unit in one run', () => {
    const ar = formatMeasurement(-1.2, 'mmol/L', 'ar');
    expect(ar).toContain('-1.2');
    expect(ar).toContain('mmol/L');
  });
});

describe('UX-01 missing translation falls back to English', () => {
  it('uses the active locale translation when it exists', () => {
    expect(resolveMessage('ar', 'signIn', messages)).toBe(messages.ar.signIn);
    expect(message('ar', 'signIn')).toBe(messages.ar.signIn);
  });

  it('falls back to the English bundle when a key is absent in Arabic', () => {
    const partial: MessageBundles = {
      en: { onlyEnglish: 'English label' },
      ar: {},
    };
    expect(resolveMessage('ar', 'onlyEnglish', partial)).toBe('English label');
  });

  it('returns the key itself when no bundle has it', () => {
    const empty: MessageBundles = { en: {}, ar: {} };
    expect(resolveMessage('ar', 'unknown.key', empty)).toBe('unknown.key');
  });
});

describe('UX-01 local-script names and mixed-script runs', () => {
  const person: PersonName = {
    latinName: 'Layla Haddad',
    localName: 'ليلى حداد',
  };

  it('annotates the Arabic name with its language and isolated direction', () => {
    expect(annotatedName(person, 'ar')).toEqual({
      text: 'ليلى حداد',
      lang: 'ar',
      dir: 'auto',
    });
  });

  it('falls back to the Latin name when Arabic has no local form', () => {
    const latinOnly: PersonName = { latinName: 'Synthetic Name' };
    expect(annotatedName(latinOnly, 'ar')).toEqual({
      text: 'Synthetic Name',
      lang: 'en',
      dir: 'auto',
    });
    expect(displayLocalName(latinOnly, 'ar')).toBe('Synthetic Name');
  });

  it('uses the Latin name in an English interface even when a local form exists', () => {
    expect(annotatedName(person, 'en')).toEqual({
      text: 'Layla Haddad',
      lang: 'en',
      dir: 'auto',
    });
  });

  it('lets a foreign clinical run resolve its own direction', () => {
    expect(mixedScriptRun('ليلى Haddad · MAIN', 'ar')).toEqual({
      text: 'ليلى Haddad · MAIN',
      lang: 'ar',
      dir: 'auto',
    });
  });
});
