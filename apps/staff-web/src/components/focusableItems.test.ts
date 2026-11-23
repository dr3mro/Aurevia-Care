import { describe, expect, it } from 'vitest';
import { focusableItems } from './focusableItems';

const selector = 'button:not([disabled])';

describe('focusableItems', () => {
  it('lists matching controls in DOM order and ignores disabled ones', () => {
    const container = document.createElement('div');
    const first = document.createElement('button');
    const disabled = document.createElement('button');
    disabled.disabled = true;
    const second = document.createElement('button');
    const anchor = document.createElement('a');
    container.append(first, disabled, second, anchor);

    expect(focusableItems(container, selector)).toEqual([first, second]);
  });

  it('returns no items when the container has been detached', () => {
    expect(focusableItems(null, selector)).toEqual([]);
  });
});
