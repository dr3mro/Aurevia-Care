import { describe, expect, it } from 'vitest';
import { unitsKeys, unitsMessages, unitsText } from './messages';
import type { Locale } from '../../i18n/messages';

describe('unit service profile messages (UX-01)', () => {
  it('defines the same keys in English and Arabic', () => {
    expect([...unitsKeys].sort()).toEqual(Object.keys(unitsMessages.ar).sort());
    expect([...unitsKeys].sort()).toEqual(Object.keys(unitsMessages.en).sort());
  });

  it.each(unitsKeys)('has a non-empty %s label in both locales', (key) => {
    expect(unitsMessages.en[key].length).toBeGreaterThan(0);
    expect(unitsMessages.ar[key].length).toBeGreaterThan(0);
  });

  it.each(['en', 'ar'] as const)('resolves a label through unitsText for %s', (locale: Locale) => {
    expect(unitsText(locale, 'serviceProfile')).toBe(unitsMessages[locale].serviceProfile);
  });
});
