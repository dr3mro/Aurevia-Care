import { describe, expect, it } from 'vitest';
import { fillMessagePlaceholders } from './messagePlaceholders';

describe('fillMessagePlaceholders', () => {
  it('substitutes every named placeholder that has a value', () => {
    expect(
      fillMessagePlaceholders('Review {survivor} and {duplicate}', {
        survivor: 's1',
        duplicate: 'd1',
      }),
    ).toBe('Review s1 and d1');
  });

  it('leaves a placeholder whose value is missing visible instead of blanking it', () => {
    expect(fillMessagePlaceholders('Review {survivor} and {duplicate}', { survivor: 's1' })).toBe(
      'Review s1 and {duplicate}',
    );
  });

  it('leaves text without placeholders unchanged when no values are supplied', () => {
    expect(fillMessagePlaceholders('No placeholders here')).toBe('No placeholders here');
  });
});
