import { describe, expect, it } from 'vitest';
import {
  blockerMessageKey,
  dependencyKindKey,
  dependencyStateKey,
  dobReasonKey,
  dobText,
  duplicateMessages,
  duplicatesKeys,
  duplicatesMessage,
  duplicatesText,
  refusalKeyForCode,
} from './messages';

describe('duplicate messages', () => {
  it('keeps identical key sets in English and Arabic', () => {
    expect(Object.keys(duplicateMessages.en).sort()).toEqual([...duplicatesKeys].sort());
    expect(Object.keys(duplicateMessages.ar).sort()).toEqual([...duplicatesKeys].sort());
  });

  it('looks up a label for either locale', () => {
    expect(duplicatesText('en', 'title')).toBe('Duplicate patient review');
    expect(duplicatesText('ar', 'title')).toBe('مراجعة المرضى المكررين');
  });

  it('fills named placeholders and leaves unknown ones visible', () => {
    expect(duplicatesMessage('en', 'reviewActionNamed', { survivor: 's1', duplicate: 'd1' })).toBe(
      'Review pair s1 and d1',
    );
    expect(duplicatesMessage('en', 'reviewActionNamed', { survivor: 's1' })).toBe(
      'Review pair s1 and {duplicate}',
    );
  });
});

describe('dependency labels', () => {
  it('maps every known kind to its own label', () => {
    expect(dependencyKindKey('ORDER')).toBe('depKindOrder');
    expect(dependencyKindKey('PATIENT_LINK')).toBe('depKindLink');
    expect(dependencyKindKey('GRANT')).toBe('depKindGrant');
  });

  it('falls back for an unknown kind', () => {
    expect(dependencyKindKey('SOMETHING_ELSE')).toBe('depKindOther');
  });

  it('maps all three resolved/ambiguous states', () => {
    expect(dependencyStateKey(true, true)).toBe('depResolved');
    expect(dependencyStateKey(false, true)).toBe('depAmbiguous');
    expect(dependencyStateKey(false, false)).toBe('depUnresolved');
  });
});

describe('blocker and refusal mapping', () => {
  it('maps each approval blocker to a message', () => {
    expect(blockerMessageKey('same-reviewer')).toBe('blockerSameReviewer');
    expect(blockerMessageKey('unresolved-dependencies')).toBe('blockerUnresolved');
    expect(blockerMessageKey('no-open-review')).toBe('blockerOpenFirst');
    expect(blockerMessageKey(undefined)).toBe('blockerOpenFirst');
  });

  it('maps the two safety-critical server codes and falls back for the rest', () => {
    expect(refusalKeyForCode('SECOND_REVIEWER_REQUIRED')).toBe('refusalSecondReviewer');
    expect(refusalKeyForCode('UNRESOLVED_DEPENDENCY')).toBe('refusalUnresolved');
    expect(refusalKeyForCode('CONFLICT')).toBe('refusalGeneric');
    expect(refusalKeyForCode(undefined)).toBe('refusalGeneric');
  });
});

describe('date of birth labels', () => {
  it('maps each coded unknown reason and falls back for unknown codes', () => {
    expect(dobReasonKey('not_known')).toBe('reasonNotKnown');
    expect(dobReasonKey('not_disclosed')).toBe('reasonNotDisclosed');
    expect(dobReasonKey('unable_to_collect')).toBe('reasonUnableToCollect');
    expect(dobReasonKey('something_else')).toBe('dobUnknown');
    expect(dobReasonKey(null)).toBe('dobUnknown');
  });

  it('formats a civil date when one is present', () => {
    expect(dobText('1990-05-12', null, 'en')).toBe('May 12, 1990');
  });

  it('renders a coded unknown reason when no date is present', () => {
    expect(dobText(null, 'not_disclosed', 'en')).toBe('Not disclosed');
    expect(dobText(null, 'not_known', 'ar')).toBe('غير معروف');
    expect(dobText(null, null, 'en')).toBe('Date of birth not recorded');
  });
});
