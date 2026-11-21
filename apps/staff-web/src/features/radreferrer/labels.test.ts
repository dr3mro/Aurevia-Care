import { describe, expect, it } from 'vitest';
import { REPORT_SECTION_KEYS } from './domain';
import { radreferrerKeys, radreferrerText, sectionMessageKey } from './labels';

describe('radreferrer messages', () => {
  it('has a distinct key for every label and no duplicate keys', () => {
    expect(new Set(radreferrerKeys).size).toBe(radreferrerKeys.length);
    expect(radreferrerKeys.length).toBeGreaterThan(10);
  });

  it('resolves a label in English and Arabic', () => {
    expect(radreferrerText('en', 'radreferrer')).toBe('Imaging report');
    expect(radreferrerText('ar', 'radreferrer')).toBe('تقرير الأشعة');
    expect(radreferrerText('ar', 'acknowledge')).toBe('إقرار الاستلام');
  });

  it('maps every signed section to a translated heading', () => {
    for (const key of REPORT_SECTION_KEYS) {
      expect(radreferrerText('en', sectionMessageKey[key])).toBeTruthy();
      expect(radreferrerText('ar', sectionMessageKey[key])).toBeTruthy();
    }
  });

  it('keeps the version placeholder in both language bundles', () => {
    for (const key of ['addendumBody', 'acknowledgeVersion'] as const) {
      expect(radreferrerText('en', key)).toContain('{version}');
      expect(radreferrerText('ar', key)).toContain('{version}');
    }
  });
});
