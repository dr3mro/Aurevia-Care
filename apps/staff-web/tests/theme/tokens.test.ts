import { describe, expect, it } from 'vitest';
import { themeTokens } from '../../src/theme/tokens';

// WCAG 2.2 relative luminance / contrast ratio (test-only helper).
function contrast(background: string, foreground: string): number {
  const luminance = (hex: string): number => {
    const channels = [1, 3, 5].map((start) => {
      const value = parseInt(hex.slice(start, start + 2), 16) / 255;
      return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
    });
    return (channels[0] ?? 0) * 0.2126 + (channels[1] ?? 0) * 0.7152 + (channels[2] ?? 0) * 0.0722;
  };
  const a = luminance(background);
  const b = luminance(foreground);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

describe('design tokens', () => {
  it('resolves light and dark token sets with an accessible focus ring', () => {
    const light = themeTokens('light');
    const dark = themeTokens('dark');
    expect(light.background).not.toBe(dark.background);
    expect(light.foreground).not.toBe(dark.foreground);
    expect(light.focusRing).toMatch(/^0 0 0 /);
    expect(dark.focusRing).toBe(light.focusRing);
    expect(light.spacing.md).toBeTruthy();
  });

  it('meets WCAG 2.2 AA 3:1 non-text contrast for control boundaries', () => {
    const light = themeTokens('light');
    const dark = themeTokens('dark');
    expect(contrast(light.background, light.border)).toBeGreaterThanOrEqual(3);
    expect(contrast(dark.background, dark.border)).toBeGreaterThanOrEqual(3);
  });
});
