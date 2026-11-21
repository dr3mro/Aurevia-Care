import { describe, expect, it } from 'vitest';
import { entryKeys, entryMessages, entryText } from './messages';

describe('order entry messages', () => {
  it('defines the same keys in English and Arabic', () => {
    expect(Object.keys(entryMessages.en).sort()).toEqual([...entryKeys].sort());
    expect(Object.keys(entryMessages.ar).sort()).toEqual([...entryKeys].sort());
  });

  it('looks up a label in both locales', () => {
    expect(entryText('en', 'panelTitle')).toBe(entryMessages.en.panelTitle);
    expect(entryText('ar', 'panelTitle')).toBe(entryMessages.ar.panelTitle);
    expect(entryText('ar', 'panelTitle')).not.toBe(entryText('en', 'panelTitle'));
  });

  it('has a clinical-question error and a duplicate warning in both locales', () => {
    expect(entryText('en', 'clinicalQuestionRequired').length).toBeGreaterThan(0);
    expect(entryText('ar', 'clinicalQuestionRequired').length).toBeGreaterThan(0);
    expect(entryText('en', 'duplicateWarning').length).toBeGreaterThan(0);
    expect(entryText('ar', 'duplicateWarning').length).toBeGreaterThan(0);
  });
});
