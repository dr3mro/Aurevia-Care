import { describe, expect, it } from 'vitest';
import {
  settingsKeys,
  settingsMessages,
  settingsText,
  unreadBadgeLabel,
} from './labels';

describe('notification settings messages', () => {
  it('has a distinct key for every label and no duplicate keys', () => {
    expect(new Set(settingsKeys).size).toBe(settingsKeys.length);
    expect(Object.keys(settingsMessages.en).sort()).toEqual([...settingsKeys].sort());
    expect(Object.keys(settingsMessages.ar).sort()).toEqual([...settingsKeys].sort());
  });

  it('has a non-empty label for every key in both bundles', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of settingsKeys) {
        expect(settingsMessages[locale][key].length).toBeGreaterThan(0);
      }
    }
  });

  it('carries Arabic script for every Arabic label', () => {
    for (const key of settingsKeys) {
      expect(settingsMessages.ar[key]).toMatch(/[\u0600-\u06FF]/);
    }
  });

  it('keeps the count placeholder in both plural badge labels in both bundles', () => {
    for (const locale of ['en', 'ar'] as const) {
      expect(settingsMessages[locale].unreadBadgeOne).toContain('{count}');
      expect(settingsMessages[locale].unreadBadgeOther).toContain('{count}');
    }
  });

  it('resolves a label from the bundle for each locale', () => {
    for (const locale of ['en', 'ar'] as const) {
      for (const key of settingsKeys) {
        expect(settingsText(locale, key)).toBe(settingsMessages[locale][key]);
      }
    }
  });

  it('substitutes the formatted count into the singular badge label', () => {
    expect(unreadBadgeLabel('en', 1)).toBe(
      settingsText('en', 'unreadBadgeOne').replace('{count}', '1'),
    );
    expect(unreadBadgeLabel('ar', 1)).toBe(
      settingsText('ar', 'unreadBadgeOne').replace('{count}', '1'),
    );
    expect(unreadBadgeLabel('en', 1)).not.toBe(
      settingsText('en', 'unreadBadgeOther').replace('{count}', '1'),
    );
  });

  it('uses the plural label for every count other than one', () => {
    expect(unreadBadgeLabel('en', 7)).toBe(
      settingsText('en', 'unreadBadgeOther').replace('{count}', '7'),
    );
    expect(unreadBadgeLabel('ar', 120)).toContain('99+');
    expect(unreadBadgeLabel('ar', 120)).not.toContain('{count}');
    expect(unreadBadgeLabel('en', 7)).not.toBe(unreadBadgeLabel('ar', 7));
  });
});
