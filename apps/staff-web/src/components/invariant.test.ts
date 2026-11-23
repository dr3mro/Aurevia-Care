import { describe, expect, it } from 'vitest';
import { defined } from './invariant';

/** The invariant must keep falsy-but-present values: only absence is an error. */
describe('defined', () => {
  it('returns present values unchanged, including falsy ones', () => {
    const syntheticValue = { id: 'synthetic-1' };
    expect(defined(syntheticValue, 'synthetic value')).toBe(syntheticValue);
    expect(defined(0, 'synthetic zero')).toBe(0);
    expect(defined('', 'synthetic empty string')).toBe('');
  });

  it('throws a descriptive error for null', () => {
    expect(() => defined(null, 'synthetic value')).toThrow(
      'Expected synthetic value to be present',
    );
  });

  it('throws a descriptive error for undefined', () => {
    expect(() => defined(undefined, 'synthetic value')).toThrow(
      'Expected synthetic value to be present',
    );
  });
});
