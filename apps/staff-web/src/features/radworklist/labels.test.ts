import { describe, expect, it } from 'vitest';
import { MODALITIES, STATUS_FLOW } from './domain';
import {
  modalityMessageKey,
  radworklistKeys,
  radworklistText,
  statusMessageKey,
  urgencyMessageKey,
} from './labels';

describe('radworklist messages', () => {
  it('has a distinct key for every label and no duplicate keys', () => {
    const unique = new Set(radworklistKeys);
    expect(unique.size).toBe(radworklistKeys.length);
    expect(radworklistKeys.length).toBeGreaterThan(30);
  });

  it('resolves a label in English and Arabic', () => {
    expect(radworklistText('en', 'radworklist')).toBe('Imaging worklist');
    expect(radworklistText('ar', 'radworklist')).toBe('قائمة أعمال الأشعة');
    expect(radworklistText('ar', 'statusReported')).toBe('تم إرسال التقرير');
  });

  it('maps every status, modality and urgency to a translated label', () => {
    for (const status of STATUS_FLOW) {
      expect(radworklistText('en', statusMessageKey[status])).toBeTruthy();
      expect(radworklistText('ar', statusMessageKey[status])).toBeTruthy();
    }
    for (const modality of MODALITIES) {
      expect(radworklistText('en', modalityMessageKey[modality])).toBeTruthy();
      expect(radworklistText('ar', modalityMessageKey[modality])).toBeTruthy();
    }
    for (const urgency of ['routine', 'urgent', 'stat'] as const) {
      expect(radworklistText('en', urgencyMessageKey[urgency])).toBeTruthy();
      expect(radworklistText('ar', urgencyMessageKey[urgency])).toBeTruthy();
    }
  });
});
