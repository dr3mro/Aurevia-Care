import { describe, expect, it } from 'vitest';
import { defined } from './invariant';

describe('defined', () => {
  it('returns the original value when it is present', () => {
    const value = { id: 'synthetic-1' };
    expect(defined(value, 'synthetic value')).toBe(value);
    expect(defined(0, 'zero')).toBe(0);
    expect(defined('', 'empty string')).toBe('');
  });

  it('throws a named error for null and for undefined', () => {
    // Both sides of the nullish check are taken deterministically so the
    // failure path reports which fixture or DOM node went missing.
    expect(() => defined(null, 'null value')).toThrow('Expected null value to be present');
    expect(() => defined(undefined, 'missing value')).toThrow(
      'Expected missing value to be present',
    );
  });
});
