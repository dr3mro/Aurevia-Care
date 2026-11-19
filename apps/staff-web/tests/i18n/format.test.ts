import { describe, expect, it } from 'vitest';
import {
  directionFor,
  displayLocalName,
  formatCivilDate,
  formatInstant,
  formatMeasurement,
  message,
  scriptAnnotation,
} from '../../src/i18n/format';
import type { PersonName } from '../../src/i18n/format';

describe('UX-01 locale direction', () => {
  it('maps English to LTR and Arabic to RTL', () => {
    expect(directionFor('en')).toBe('ltr');
    expect(directionFor('ar')).toBe('rtl');
  });
});

describe('UX-01 local name display', () => {
  const person: PersonName = { latinName: 'Layla Haddad', localName: 'ليلى حداد' };

  it('shows the local name for Arabic', () => {
    expect(displayLocalName(person, 'ar')).toBe('ليلى حداد');
  });

  it('shows the Latin name for English', () => {
    expect(displayLocalName(person, 'en')).toBe('Layla Haddad');
  });

  it('falls back to the Latin name when no local name is recorded', () => {
    expect(displayLocalName({ latinName: 'Synthetic Only' }, 'ar')).toBe('Synthetic Only');
  });
});

describe('UX-01 instants, civil dates and DST', () => {
  it('renders the same UTC instant differently in two IANA zones', () => {
    const instant = '2026-06-15T12:00:00Z';
    const utc = formatInstant(instant, 'UTC', 'en');
    const riyadh = formatInstant(instant, 'Asia/Riyadh', 'en');
    expect(utc).not.toBe(riyadh);
    expect(riyadh).toContain('15');
  });

  it('distinguishes the two instants around a spring-forward transition', () => {
    const before = formatInstant('2026-03-08T06:30:00Z', 'America/New_York', 'en');
    const after = formatInstant('2026-03-08T07:30:00Z', 'America/New_York', 'en');
    expect(before).not.toBe(after);
    expect(before).toMatch(/1:30/);
    expect(after).toMatch(/3:30/);
  });

  it('formats a civil date without shifting the calendar day', () => {
    const en = formatCivilDate('2026-02-28', 'en');
    const ar = formatCivilDate('2026-02-28', 'ar');
    expect(en).toContain('28');
    expect(ar.length).toBeGreaterThan(0);
    expect(ar).not.toBe(en);
  });
});

describe('UX-01 units and mixed script', () => {
  it('preserves the supplied unit for both locales using locale number rules', () => {
    const en = formatMeasurement(12.5, 'cm', 'en');
    const ar = formatMeasurement(12.5, 'cm', 'ar');
    expect(en).toBe('12.5 cm');
    expect(ar).toContain('cm');
    expect(ar.length).toBeGreaterThan(0);
  });

  it('annotates mixed-script text with its language and direction', () => {
    expect(scriptAnnotation('ليلى Haddad', 'ar', 'rtl')).toEqual({
      text: 'ليلى Haddad',
      lang: 'ar',
      dir: 'rtl',
    });
  });
});

describe('localized messages', () => {
  it('returns the bundle value for the requested locale', () => {
    expect(message('en', 'signIn')).toBeTruthy();
    expect(message('ar', 'signIn')).not.toBe(message('en', 'signIn'));
  });
});
