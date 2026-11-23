import { describe, expect, it } from 'vitest';
import { defined } from './invariant';

describe('defined', () => {
  it('returns a present value unchanged', () => {
    const value = { id: 'synthetic-1' };
    expect(defined(value, 'synthetic value')).toBe(value);
  });

  it.each([null, undefined])('throws a labelled error for %s', (missing) => {
    expect(() => defined(missing, 'synthetic value')).toThrow(
      'Expected synthetic value to be present',
    );
  });
});
