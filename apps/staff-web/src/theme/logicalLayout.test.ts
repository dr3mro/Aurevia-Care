import { describe, expect, it } from 'vitest';
import type { CSSProperties } from 'react';
import { logicalContentOffset } from './logicalLayout';
import { themeTokens } from './tokens';

const PHYSICAL_SIDE = /^(margin|padding)(Left|Right)$|^(left|right)$|^border(Left|Right)/;

describe('UX-01 logical layout properties', () => {
  it('uses only logical side properties and theme-derived spacing', () => {
    const tokens = themeTokens('light');
    const style: CSSProperties = logicalContentOffset(tokens);
    const keys = Object.keys(style);

    expect(keys.length).toBeGreaterThan(0);
    for (const key of keys) {
      expect(key).not.toMatch(PHYSICAL_SIDE);
    }
    expect(style.marginInlineStart).toBe('0');
    expect(style.marginInlineEnd).toBe(tokens.spacing.md);
    expect(style.paddingInlineStart).toBe(tokens.spacing.md);
    expect(style.paddingInlineEnd).toBe(tokens.spacing.md);
    expect(style.borderInlineStart).toContain(tokens.border);
    expect(style.textAlign).toBe('start');
  });

  it('takes the border colour from the dark theme without changing property names', () => {
    const tokens = themeTokens('dark');
    const style = logicalContentOffset(tokens);
    expect(style.borderInlineStart).toContain(tokens.border);
    expect(style.borderInlineStart).not.toContain(themeTokens('light').border);
    expect(style.paddingInlineStart).toBe(tokens.spacing.md);
  });
});
