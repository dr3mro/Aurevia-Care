import { describe, expect, it } from 'vitest';
import {
  dobReasonKey,
  patientMessages,
  patientsKeys,
  patientsMessage,
  patientsText,
} from './messages';

describe('patient messages', () => {
  it('keeps identical key sets in English and Arabic', () => {
    expect(Object.keys(patientMessages.en).sort()).toEqual([...patientsKeys].sort());
    expect(Object.keys(patientMessages.ar).sort()).toEqual([...patientsKeys].sort());
  });

  it('looks up a label for either locale', () => {
    expect(patientsText('en', 'patients')).toBe('Patients');
    expect(patientsText('ar', 'patients')).toBe('المرضى');
  });

  it('fills named placeholders and leaves unknown ones visible', () => {
    expect(patientsMessage('en', 'registered', { name: 'Layla' })).toBe('Registered Layla');
    expect(patientsMessage('en', 'registered', {})).toBe('Registered {name}');
  });
});

describe('unknown-date reason labels', () => {
  it('maps every coded reason to its message key', () => {
    expect(dobReasonKey('not_known')).toBe('reasonNotKnown');
    expect(dobReasonKey('not_disclosed')).toBe('reasonNotDisclosed');
    expect(dobReasonKey('unable_to_collect')).toBe('reasonUnableToCollect');
  });

  it('falls back for an unmapped or missing code', () => {
    expect(dobReasonKey('other')).toBe('reasonUnknown');
    expect(dobReasonKey(null)).toBe('reasonUnknown');
    expect(dobReasonKey(undefined)).toBe('reasonUnknown');
  });
});
