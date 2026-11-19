import { describe, expect, it } from 'vitest';
import { messageKeys, messages } from '../../src/i18n/messages';

describe('UX-01 bilingual resource bundles', () => {
  it('defines the same non-empty keys in English and Arabic', () => {
    const enKeys = Object.keys(messages.en).sort();
    const arKeys = Object.keys(messages.ar).sort();
    expect(enKeys).toEqual([...messageKeys].sort());
    expect(arKeys).toEqual(enKeys);
    for (const key of messageKeys) {
      expect(messages.en[key].length).toBeGreaterThan(0);
      expect(messages.ar[key].length).toBeGreaterThan(0);
    }
  });

  it('contains Arabic script and a mixed-script clinical example', () => {
    expect(messages.ar.appTitle).toMatch(/[\u0600-\u06FF]/);
    expect(messages.ar.mixedScriptNote).toMatch(/[A-Za-z0-9]/);
    expect(messages.ar.mixedScriptNote).toMatch(/[\u0600-\u06FF]/);
  });
});
