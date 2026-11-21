import { describe, expect, it } from 'vitest';
import {
  blockerKey,
  prescriptionMessageKeys,
  prescriptionMessages,
  prescriptionText,
} from './labels';

describe('prescription print messages', () => {
  it('keeps identical key sets in English and Arabic', () => {
    const expected = [...prescriptionMessageKeys].sort();
    expect(Object.keys(prescriptionMessages.en).sort()).toEqual(expected);
    expect(Object.keys(prescriptionMessages.ar).sort()).toEqual(expected);
  });

  it.each(prescriptionMessageKeys)('resolves %s to a non-empty string in both languages', (key) => {
    expect(prescriptionText('en', key)).toBe(prescriptionMessages.en[key]);
    expect(prescriptionText('ar', key)).toBe(prescriptionMessages.ar[key]);
    expect(prescriptionText('en', key).length).toBeGreaterThan(0);
    expect(prescriptionText('ar', key).length).toBeGreaterThan(0);
  });

  it('maps each blocker to a distinct, defined bilingual label', () => {
    const keys = Object.values(blockerKey);
    expect(new Set(keys).size).toBe(keys.length);
    expect(keys.every((key) => prescriptionText('en', key).length > 0)).toBe(true);
    expect(keys.every((key) => prescriptionText('ar', key).length > 0)).toBe(true);
  });
});
