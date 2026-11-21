import { describe, expect, it } from 'vitest';
import {
  radiologyMessages,
  radiologyText,
  stateMessageKey,
  urgencyMessageKey,
} from './messages';
import type { ImagingOrderState, Urgency } from './domain';

describe('radiography messages', () => {
  it('keeps identical key sets in English and Arabic', () => {
    expect(Object.keys(radiologyMessages.en).sort()).toEqual(
      Object.keys(radiologyMessages.ar).sort(),
    );
  });

  it('looks up a label for either locale', () => {
    expect(radiologyText('en', 'radiology')).toBe('Radiology');
    expect(radiologyText('ar', 'radiology')).toBe('الأشعة');
  });

  it('maps every order state and urgency to a label key', () => {
    const states: readonly ImagingOrderState[] = [
      'received',
      'scheduled',
      'study-linked',
      'interpreted',
      'addended',
    ];
    const urgencies: readonly Urgency[] = ['routine', 'urgent', 'stat'];
    for (const state of states) {
      expect(radiologyText('en', stateMessageKey[state])).toBeTruthy();
    }
    for (const urgency of urgencies) {
      expect(radiologyText('ar', urgencyMessageKey[urgency])).toBeTruthy();
    }
  });
});
