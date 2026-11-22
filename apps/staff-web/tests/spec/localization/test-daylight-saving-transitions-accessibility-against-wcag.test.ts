import { fireEvent, render, screen } from '@testing-library/react';
import { createElement } from 'react';
import { describe, expect, it } from 'vitest';
import { AppShell } from '../../../src/app/AppShell';
import { RouterProvider } from '../../../src/app/router';
import { routes } from '../../../src/app/routes';
import {
  syntheticCivilDate,
  syntheticInstant,
  syntheticTimeZone,
} from '../../../src/fixtures/synthetic';
import { formatCivilDate, formatInstant } from '../../../src/i18n/format';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

/** Render the real shell at a path, with the same providers the app mounts. */
function renderShellAt(path: string) {
  return render(
    createElement(
      ThemeProvider,
      null,
      createElement(
        I18nProvider,
        null,
        createElement(RouterProvider, {
          patterns: routes.map((route) => route.pattern),
          initialPath: path,
          children: createElement(AppShell, {}),
        }),
      ),
    ),
  );
}

describe('UX-01 clause 3 — daylight-saving transitions', () => {
  it('rendersDSTTransitionsAsDistinctLocalTimes', () => {
    // Spring forward: a one-hour UTC step becomes a two-hour local jump because
    // the clock skips 02:00 in America/New_York on 2026-03-08.
    const beforeSpring = formatInstant(syntheticInstant, syntheticTimeZone, 'en');
    const afterSpring = formatInstant('2026-03-08T07:30:00Z', syntheticTimeZone, 'en');
    expect(beforeSpring).toMatch(/1:30/);
    expect(afterSpring).toMatch(/3:30/);
    expect(beforeSpring).not.toBe(afterSpring);

    // Fall back: a one-hour UTC step repeats the same wall clock as the offset
    // changes from EDT to EST, so the two outputs are identical. This is the
    // property a naive UTC-plus-fixed-offset formatter would get wrong.
    const duringOverlap = formatInstant('2026-11-01T05:30:00Z', syntheticTimeZone, 'en');
    const afterOverlap = formatInstant('2026-11-01T06:30:00Z', syntheticTimeZone, 'en');
    expect(duringOverlap).toMatch(/1:30/);
    expect(afterOverlap).toMatch(/1:30/);
    expect(afterOverlap).toBe(duringOverlap);
  });

  it('keepsCivilDateOnTheRecordedDay', () => {
    const formatted = formatCivilDate(syntheticCivilDate, 'en');
    expect(formatted).toContain('February 28');
    expect(formatted).not.toContain('March');
  });

  it('rejectsUnknownIANAZone', () => {
    expect(() => formatInstant(syntheticInstant, 'Not/AZone', 'en')).toThrow(RangeError);
  });

  it('sameInstantDiffersBetweenZones', () => {
    const utc = formatInstant(syntheticInstant, 'UTC', 'en');
    const riyadh = formatInstant(syntheticInstant, 'Asia/Riyadh', 'en');
    expect(utc).toContain('6:30');
    expect(riyadh).toContain('9:30');
    expect(riyadh).not.toBe(utc);
  });
});

describe('UX-01 clause 3 — WCAG 2.2 AA patterns on staff web', () => {
  it('exposesAccessibleNamesRolesAndPressedState', () => {
    renderShellAt('/');

    expect(screen.getByRole('banner')).toBeTruthy();
    expect(screen.getByRole('main')).toBeTruthy();
    const status = screen.getByRole('status', { name: 'Notifications' });
    expect(status.getAttribute('aria-live')).toBe('polite');
    expect(screen.getByRole('group', { name: 'Sections' })).toBeTruthy();

    // The skip link is the first focusable control and targets the main region.
    const skip = screen.getByRole('link', { name: /skip to main/i });
    expect(skip.getAttribute('href')).toBe('#main');
    const banner = screen.getByRole('banner');
    expect(skip.compareDocumentPosition(banner) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    // A toggle's state is exposed as pressed, not only by its painted colour.
    const theme = screen.getByRole('button', { name: 'Theme' });
    expect(theme.getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(theme);
    expect(theme.getAttribute('aria-pressed')).toBe('true');
  });
});
