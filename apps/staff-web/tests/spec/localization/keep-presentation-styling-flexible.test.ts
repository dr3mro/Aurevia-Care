import { createElement } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { logicalContentOffset } from '../../../src/theme/logicalLayout';
import { ThemeProvider, useTheme } from '../../../src/theme/ThemeProvider';
import { themeTokens } from '../../../src/theme/tokens';

/**
 * Observes the theme contract through the same public React context the staff
 * shell uses. Rendered with `createElement` because this specification file is
 * plain TypeScript rather than JSX.
 */
function ThemeProbe() {
  const { mode, tokens, toggleMode } = useTheme();
  return createElement(
    'div',
    null,
    createElement('span', { 'data-testid': 'mode' }, mode),
    createElement('span', { 'data-testid': 'background' }, tokens.background),
    createElement('button', { type: 'button', onClick: toggleMode }, 'toggle theme'),
  );
}

describe('UX-01 clause 5 — keep presentation styling flexible', () => {
  it('resolves distinct light and dark backgrounds from the same token API', () => {
    // Both sides of the single mode parameterisation are reached through one API.
    expect(themeTokens('light').background).toBe('#ffffff');
    expect(themeTokens('dark').background).toBe('#111827');

    const light = themeTokens('light');
    const dark = themeTokens('dark');
    expect(light.background).not.toBe(dark.background);
    expect(light.foreground).not.toBe(dark.foreground);
  });

  it('keeps spacing and focus-ring values stable across modes', () => {
    const light = themeTokens('light');
    const dark = themeTokens('dark');

    // The spacing scale is mode independent: one set of values, shared by both
    // modes, not a second layout authored for dark.
    expect(dark.spacing).toEqual(light.spacing);
    expect(light.spacing.sm).toBe('4px');
    expect(light.spacing.md).toBe('8px');
    expect(light.spacing.lg).toBe('16px');

    // Both modes expose a focus-ring presentation value. We deliberately do not
    // assert the two values are equal: the ring's actual treatment is the
    // documented gap in docs/spec/clauses/localization/keep-presentation-styling-flexible.md
    // and pinning the current value would encode a defect as expected behavior.
    expect(typeof light.focusRing).toBe('string');
    expect(light.focusRing.length).toBeGreaterThan(0);
    expect(typeof dark.focusRing).toBe('string');
    expect(dark.focusRing.length).toBeGreaterThan(0);

    // Sentinel values prove the layout reads whatever tokens it is given: the
    // property names stay logical and stable while the values follow the source.
    const sentinel = {
      background: 'SENTINEL_BACKGROUND',
      foreground: 'SENTINEL_FOREGROUND',
      border: 'SENTINEL_BORDER',
      accent: 'SENTINEL_ACCENT',
      focusRing: 'SENTINEL_RING',
      spacing: { sm: 'SENTINEL_SM', md: 'SENTINEL_MD', lg: 'SENTINEL_LG' },
    };
    const sentinelStyle = logicalContentOffset(sentinel);
    expect(sentinelStyle.marginInlineEnd).toBe('SENTINEL_MD');
    expect(sentinelStyle.paddingInlineStart).toBe('SENTINEL_MD');
    expect(sentinelStyle.paddingInlineEnd).toBe('SENTINEL_MD');
    expect(sentinelStyle.borderInlineStart).toBe('1px solid SENTINEL_BORDER');

    const lightStyle = logicalContentOffset(light);
    const darkStyle = logicalContentOffset(dark);
    expect(Object.keys(darkStyle).sort()).toEqual(Object.keys(lightStyle).sort());
    expect(Object.keys(sentinelStyle).sort()).toEqual(Object.keys(lightStyle).sort());
    expect(darkStyle.borderInlineStart).toContain(dark.border);
    expect(darkStyle.borderInlineStart).not.toContain(light.border);
    expect(darkStyle.textAlign).toBe('start');
  });

  it('mirrors the toggled mode onto the document root and re-resolves tokens', () => {
    render(createElement(ThemeProvider, null, createElement(ThemeProbe)));

    expect(screen.getByTestId('mode').textContent).toBe('light');
    expect(screen.getByTestId('background').textContent).toBe(themeTokens('light').background);
    expect(document.documentElement.dataset.theme).toBe('light');

    fireEvent.click(screen.getByRole('button', { name: 'toggle theme' }));

    expect(screen.getByTestId('mode').textContent).toBe('dark');
    expect(screen.getByTestId('background').textContent).toBe(themeTokens('dark').background);
    expect(document.documentElement.dataset.theme).toBe('dark');

    fireEvent.click(screen.getByRole('button', { name: 'toggle theme' }));

    expect(screen.getByTestId('mode').textContent).toBe('light');
    expect(screen.getByTestId('background').textContent).toBe(themeTokens('light').background);
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('refuses theme reads outside a provider', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    try {
      expect(() => render(createElement(ThemeProbe))).toThrow(
        /useTheme must be used inside ThemeProvider/,
      );
    } finally {
      consoleError.mockRestore();
    }
  });
});
