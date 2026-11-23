import { describe, expect, it } from 'vitest';
import { focusableItems } from './focus';

describe('focusableItems', () => {
  it('lists native controls in DOM order and skips disabled ones and non-controls', () => {
    const container = document.createElement('div');
    container.innerHTML = `
      <button type="button" id="first">First</button>
      <button type="button" id="disabled" disabled>Disabled</button>
      <select id="language"><option>Synthetic</option></select>
      <input id="reason" />
      <iframe title="synthetic preview"></iframe>
    `;

    expect(focusableItems(container).map((item) => item.id)).toEqual([
      'first',
      'language',
      'reason',
    ]);
  });

  it('returns an empty list before the dialog is mounted', () => {
    expect(focusableItems(null)).toEqual([]);
  });
});
