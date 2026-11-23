import { describe, expect, it } from 'vitest';
import { defined } from './invariant';

describe('defined', () => {
  it('returns a present value unchanged and preserves its type', () => {
    const value = { id: 'synthetic-1' };
    expect(defined(value, 'synthetic record')).toBe(value);
    expect(defined(0, 'synthetic count')).toBe(0);
    expect(defined('', 'synthetic label')).toBe('');
    expect(defined(false, 'synthetic flag')).toBe(false);
  });

  it('throws a descriptive error for null', () => {
    expect(() => defined(null, 'synthetic null value')).toThrow(
      'Expected synthetic null value to be present',
    );
  });

  it('throws a descriptive error for undefined', () => {
    expect(() => defined(undefined, 'synthetic missing value')).toThrow(
      'Expected synthetic missing value to be present',
    );
  });
});
