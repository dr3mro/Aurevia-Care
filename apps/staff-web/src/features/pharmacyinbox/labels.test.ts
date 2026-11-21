import { describe, expect, it } from 'vitest';
import { PRESCRIPTION_STATES, STATUS_FILTERS } from './types';
import {
  filterMessageKey,
  pharmacyMessages,
  pharmacyKeys,
  pharmacyText,
  stateMessageKey,
} from './labels';

describe('pharmacy messages', () => {
  it('keeps identical key sets in English and Arabic', () => {
    expect(Object.keys(pharmacyMessages.en).sort()).toEqual([...pharmacyKeys].sort());
    expect(Object.keys(pharmacyMessages.ar).sort()).toEqual([...pharmacyKeys].sort());
  });

  it('looks up a label for either locale', () => {
    expect(pharmacyText('en', 'pharmacy')).toBe('Pharmacy inbox');
    expect(pharmacyText('ar', 'pharmacy')).toBe('صندوق الصيدلية');
  });

  it('maps every prescription state to its own label key', () => {
    const keys = PRESCRIPTION_STATES.map((state) => stateMessageKey(state));
    expect(new Set(keys).size).toBe(PRESCRIPTION_STATES.length);
    for (const state of PRESCRIPTION_STATES) {
      expect(pharmacyText('en', stateMessageKey(state))).not.toBe('');
      expect(pharmacyText('ar', stateMessageKey(state))).not.toBe('');
    }
  });

  it('maps every status filter to its own label key', () => {
    const keys = STATUS_FILTERS.map((filter) => filterMessageKey(filter));
    expect(new Set(keys).size).toBe(STATUS_FILTERS.length);
    for (const filter of STATUS_FILTERS) {
      expect(pharmacyText('en', filterMessageKey(filter))).not.toBe('');
      expect(pharmacyText('ar', filterMessageKey(filter))).not.toBe('');
    }
  });
});
