import { describe, expect, it } from 'vitest';
import type { Locale } from '../../i18n/messages';
import {
  prescriberKeys,
  prescriberMessages,
  partyMessageKey,
  prescriberText,
  reasonMessageKey,
  stateMessageKey,
  statusRowLabel,
} from './labels';

describe('prescriber label bundles', () => {
  it('gives every key a non-empty value in both languages', () => {
    const locales: readonly Locale[] = ['en', 'ar'];
    for (const key of prescriberKeys) {
      for (const locale of locales) {
        expect(prescriberMessages[locale][key].length).toBeGreaterThan(0);
      }
    }
  });

  it('resolves the active locale for a visible label', () => {
    expect(prescriberText('en', 'timelineTitle')).toBe('Pharmacy status timeline');
    expect(prescriberText('ar', 'timelineTitle')).toBe('الجدول الزمني لحالة الصيدلية');
    expect(prescriberText('ar', 'approve')).toBe('موافقة');
  });

  it('maps proposal states and reasons to stable bilingual keys', () => {
    expect(stateMessageKey('pending')).toBe('statePending');
    expect(stateMessageKey('approved')).toBe('stateApproved');
    expect(stateMessageKey('declined')).toBe('stateDeclined');
    expect(reasonMessageKey('stock')).toBe('reasonStock');
    expect(reasonMessageKey('brand')).toBe('reasonBrand');
    expect(reasonMessageKey('formulation')).toBe('reasonFormulation');
  });

  it('gives every tracked event type its own label key', () => {
    expect(statusRowLabel('submitted')).not.toBe(statusRowLabel('delivery_confirmed'));
    expect(statusRowLabel('accepted')).not.toBe(statusRowLabel('dispensed'));
    expect(statusRowLabel('clarification')).toBe('eventClarification');
  });

  it('names each clarification party', () => {
    expect(partyMessageKey('pharmacy')).toBe('authorPharmacy');
    expect(partyMessageKey('prescriber')).toBe('authorPrescriber');
  });
});
