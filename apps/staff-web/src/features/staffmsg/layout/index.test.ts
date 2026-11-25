import { describe, expect, it } from 'vitest';
import { layoutText, layoutFor, MessagePaneLayout } from './index';

describe('staff message layout public entry point', () => {
  it('exports the wrapper, the pure layout resolver and the label lookup', () => {
    expect(typeof MessagePaneLayout).toBe('function');
    expect(typeof layoutFor).toBe('function');
    expect(typeof layoutText).toBe('function');
  });
});
