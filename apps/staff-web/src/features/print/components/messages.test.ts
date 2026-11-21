import { describe, expect, it } from 'vitest';
import {
  printMessageKeys,
  printMessages,
  printReasonKey,
  printText,
  refusalReasonKey,
} from './messages';

describe('print preview messages', () => {
  it('keeps identical key sets in English and Arabic', () => {
    const expected = [...printMessageKeys].sort();
    expect(Object.keys(printMessages.en).sort()).toEqual(expected);
    expect(Object.keys(printMessages.ar).sort()).toEqual(expected);
  });

  it.each(printMessageKeys)('resolves %s to a non-empty string in both languages', (key) => {
    expect(printText('en', key)).toBe(printMessages.en[key]);
    expect(printText('ar', key)).toBe(printMessages.ar[key]);
    expect(printText('en', key).length).toBeGreaterThan(0);
    expect(printText('ar', key).length).toBeGreaterThan(0);
  });

  it('maps each print reason to a distinct translated label', () => {
    expect(printReasonKey['give-to-patient']).toBe('reasonGiveToPatient');
    expect(printReasonKey.referral).toBe('reasonReferral');
    expect(printReasonKey['patient-request']).toBe('reasonPatientRequest');
    expect(new Set(Object.values(printReasonKey)).size).toBe(3);
  });

  it('maps each refusal reason to a distinct worded label', () => {
    const keys = Object.values(refusalReasonKey);
    expect(new Set(keys).size).toBe(keys.length);
    expect(keys.every((key) => printText('en', key).length > 0)).toBe(true);
    expect(keys.every((key) => printText('ar', key).length > 0)).toBe(true);
  });
});
