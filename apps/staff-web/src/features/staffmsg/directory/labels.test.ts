import { describe, expect, it } from 'vitest';
import {
  CROSS_FACILITY_MESSAGING_DISABLED,
  CROSS_ORGANIZATION_MESSAGING_DISABLED,
} from './domain';
import { directoryKeys, directoryMessages, directoryText, refusalKeyForCode } from './labels';

describe('directory messages', () => {
  it('has a distinct key for every label and no duplicate keys', () => {
    expect(new Set(directoryKeys).size).toBe(directoryKeys.length);
    expect([...directoryKeys].sort()).toEqual(
      [
        'blockedFacilityReason',
        'blockedOrgReason',
        'empty',
        'facilityLabel',
        'keyboardHint',
        'otherFacility',
        'search',
        'searchHint',
        'selected',
        'syntheticNotice',
        'title',
      ].sort(),
    );
  });

  it('has a non-empty label for every key in both bundles', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of directoryKeys) {
        expect(directoryMessages[locale][key].length).toBeGreaterThan(0);
      }
    }
  });

  it('resolves a label from the bundle for each locale', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of directoryKeys) {
        expect(directoryText(locale, key)).toBe(directoryMessages[locale][key]);
      }
    }
  });

  it('carries each authoritative refusal code in its own message in both locales', () => {
    for (const locale of ['en', 'ar'] as const) {
      expect(directoryText(locale, 'blockedOrgReason')).toContain(
        CROSS_ORGANIZATION_MESSAGING_DISABLED,
      );
      expect(directoryText(locale, 'blockedFacilityReason')).toContain(
        CROSS_FACILITY_MESSAGING_DISABLED,
      );
    }
  });

  it('maps each refusal code to its own translated label with a safe fallback', () => {
    expect(refusalKeyForCode(CROSS_ORGANIZATION_MESSAGING_DISABLED)).toBe('blockedOrgReason');
    expect(refusalKeyForCode(CROSS_FACILITY_MESSAGING_DISABLED)).toBe('blockedFacilityReason');
    expect(refusalKeyForCode(undefined)).toBe('blockedOrgReason');
    expect(refusalKeyForCode('UNKNOWN_CODE')).toBe('blockedOrgReason');
    expect(refusalKeyForCode(CROSS_FACILITY_MESSAGING_DISABLED)).not.toBe(
      refusalKeyForCode(CROSS_ORGANIZATION_MESSAGING_DISABLED),
    );
  });

  it('translates the title, marker and both refusals into Arabic', () => {
    for (const key of ['title', 'otherFacility', 'blockedOrgReason', 'blockedFacilityReason'] as const) {
      expect(directoryText('ar', key)).not.toBe(directoryText('en', key));
      expect(directoryText('ar', key)).toMatch(/[\u0600-\u06FF]/);
    }
  });
});
