import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  FieldErrorSummary,
  type FieldErrorLink,
} from '../../../src/features/admin/FieldErrorSummary';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';

type ChangeListener = (event: MediaQueryListEvent) => void;
type Breakpoint = 'compact' | 'medium' | 'expanded';

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

interface WidthCase {
  width: number;
  breakpoint: Breakpoint;
  coarse: boolean;
}

const SYNTHETIC_ERRORS: readonly FieldErrorLink[] = [
  { id: 'patient-name', label: 'Patient name', message: 'Enter the recorded name.' },
  { id: 'visit-date', label: 'Visit date', message: 'Choose a date inside the encounter.' },
  { id: 'clinical-note', label: 'Clinical note', message: 'Describe the reason for the visit.' },
];

const LONG_ENGLISH: readonly FieldErrorLink[] = [
  {
    id: 'long-english',
    label: 'A deliberately long English field label for a narrow clinical form',
    message:
      'This synthetic validation message is deliberately long so it must wrap across several lines on a 320 pixel phone without being clipped, truncated or forcing a horizontal page scroll.',
  },
];

const LONG_ARABIC: readonly FieldErrorLink[] = [
  {
    id: 'long-arabic',
    label: 'تسمية حقل عربية طويلة جداً لنموذج سريري ضيق',
    message:
      'هذه رسالة تحقق تجريبية عربية طويلة جداً يجب أن تلتف عبر عدة أسطر على شاشة هاتف ضيقة دون قص أو اقتطاع أو فرض تمرير أفقي للصفحة',
  },
];

/**
 * Deterministic matchMedia stand-in driven by a synthetic viewport width. It
 * answers the two `min-width` layout queries and the coarse-pointer query the
 * way a browser would, and records listeners so a resize can be fired later.
 * jsdom cannot lay out pixels, so assertions read the observable classes, data
 * attributes, styles and DOM order rather than measured boxes.
 */
function installMatchMedia(width: number, coarse: boolean): Map<string, QueryState> {
  const states = new Map<string, QueryState>();
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => {
      let matches = false;
      if (query === BREAKPOINT_MEDIA_QUERIES.medium) {
        matches = width >= 768;
      } else if (query === BREAKPOINT_MEDIA_QUERIES.expanded) {
        matches = width >= 1280;
      } else if (query === '(pointer: coarse)') {
        matches = coarse;
      }
      const state = states.get(query) ?? { matches, listeners: new Set<ChangeListener>() };
      states.set(query, state);
      return {
        get matches() {
          return state.matches;
        },
        addEventListener: (_type: string, listener: ChangeListener) => {
          state.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          state.listeners.delete(listener);
        },
      };
    }),
  );
  return states;
}

/** Render the summary under the theme provider the component requires. */
function renderSummary(title: string, errors: readonly FieldErrorLink[]) {
  return render(
    <ThemeProvider>
      <FieldErrorSummary title={title} errors={errors} />
    </ThemeProvider>,
  );
}

/** The single alert region the summary renders. */
function alertRegion(): HTMLElement {
  return screen.getByRole('alert');
}

/** Physical side properties are a defect in shared components (WEB-07, UX-01). */
function assertLogicalOnly(style: string): void {
  expect(style).not.toMatch(/padding-left|padding-right|margin-left|margin-right/);
  expect(style).not.toMatch(/border-left|border-right|text-align\s*:\s*(?:left|right)/);
}

const widthCases: readonly WidthCase[] = [
  { width: 320, breakpoint: 'compact', coarse: true },
  { width: 360, breakpoint: 'compact', coarse: true },
  { width: 390, breakpoint: 'compact', coarse: true },
  { width: 768, breakpoint: 'medium', coarse: true },
  { width: 820, breakpoint: 'medium', coarse: true },
  { width: 1024, breakpoint: 'medium', coarse: true },
  { width: 1280, breakpoint: 'expanded', coarse: false },
];

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.removeAttribute('dir');
  document.documentElement.removeAttribute('lang');
});

describe('responsive FieldErrorSummary (WEB-07, UX-01)', () => {
  it.each(widthCases)(
    'renders a stacked, labelled alert with tappable links at $width px ($breakpoint)',
    ({ width, breakpoint, coarse }) => {
      installMatchMedia(width, coarse);
      const { container } = renderSummary('Review the form', SYNTHETIC_ERRORS);

      const alert = alertRegion();
      expect(alert.tagName).toBe('DIV');
      expect(alert.getAttribute('role')).toBe('alert');
      expect(alert.getAttribute('aria-label')).toBe('Review the form');
      expect(alert.classList.contains('stack')).toBe(true);
      expect(alert.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(alert.getAttribute('data-coarse-pointer')).toBe(String(coarse));

      const style = alert.getAttribute('style') ?? '';
      expect(style).toContain('overflow-wrap: anywhere');
      expect(style).toContain('text-align: start');
      expect(style).not.toContain('display: none');
      assertLogicalOnly(style);

      expect(screen.getByRole('heading', { level: 3 }).textContent).toBe('Review the form');
      const links = screen.getAllByRole('link');
      expect(links.map((link) => link.textContent)).toEqual([
        'Patient name: Enter the recorded name.',
        'Visit date: Choose a date inside the encounter.',
        'Clinical note: Describe the reason for the visit.',
      ]);
      for (const link of links) {
        expect(link.classList.contains('touch')).toBe(true);
        expect(link.getAttribute('style') ?? '').not.toContain('display: none');
        assertLogicalOnly(link.getAttribute('style') ?? '');
      }
      expect(container.querySelector('[style*="display: none"]')).toBeNull();
    },
  );

  it('keeps long English messages wrapped, associated and untruncated', () => {
    installMatchMedia(320, true);
    renderSummary('Long English summary', LONG_ENGLISH);

    const link = screen.getByRole<HTMLAnchorElement>('link');
    expect(link.getAttribute('href')).toBe('#long-english');
    expect(link.textContent).toContain(LONG_ENGLISH[0]?.message);
    const style = link.getAttribute('style') ?? '';
    expect(style).toContain('overflow-wrap: anywhere');
    expect(style).toContain('white-space: normal');
    expect(link.style.textOverflow).toBe('');
    expect(alertRegion().textContent).toContain(LONG_ENGLISH[0]?.label);
  });

  it('mirrors a long Arabic RTL summary without direction overrides or hidden text', () => {
    document.documentElement.setAttribute('dir', 'rtl');
    document.documentElement.setAttribute('lang', 'ar');
    installMatchMedia(360, true);
    const { container } = renderSummary('راجع النموذج', LONG_ARABIC);

    expect(document.documentElement.dir).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar');
    const alert = alertRegion();
    expect(alert.getAttribute('aria-label')).toBe('راجع النموذج');
    expect(alert.getAttribute('data-breakpoint')).toBe('compact');
    expect(screen.getByRole('heading', { level: 3 }).textContent).toBe('راجع النموذج');

    const link = screen.getByRole<HTMLAnchorElement>('link');
    expect(link.getAttribute('href')).toBe('#long-arabic');
    expect(link.textContent).toContain(LONG_ARABIC[0]?.message);
    expect(link.style.direction).toBe('');
    expect(alert.style.direction).toBe('');
    expect(container.querySelector('[style*="display: none"]')).toBeNull();
    assertLogicalOnly(link.getAttribute('style') ?? '');
  });

  it('preserves the keyboard order and focusability of every link', () => {
    installMatchMedia(1280, false);
    const { container } = renderSummary('Focus order', SYNTHETIC_ERRORS);

    const links = screen.getAllByRole<HTMLAnchorElement>('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '#patient-name',
      '#visit-date',
      '#clinical-note',
    ]);
    for (const link of links) {
      expect(link.tagName).toBe('A');
      expect(link.tabIndex).toBe(0);
      link.focus();
      expect(document.activeElement).toBe(link);
    }
    const focusable = container.querySelectorAll('a[href], button, input, select, textarea');
    expect(Array.from(focusable)).toEqual(links);
  });

  it('renders no alert for an empty error list', () => {
    installMatchMedia(320, true);
    const { container } = renderSummary('Nothing wrong', []);
    expect(screen.queryByRole('alert')).toBeNull();
    expect(container.firstChild).toBeNull();
  });

  it('follows a viewport change without remounting or altering the messages', () => {
    const states = installMatchMedia(320, true);
    renderSummary('Review the form', SYNTHETIC_ERRORS);

    const alert = alertRegion();
    expect(alert.getAttribute('data-breakpoint')).toBe('compact');

    const medium = states.get(BREAKPOINT_MEDIA_QUERIES.medium);
    expect(medium).toBeDefined();
    act(() => {
      if (!medium) {
        return;
      }
      medium.matches = true;
      for (const listener of medium.listeners) {
        listener({ matches: true } as MediaQueryListEvent);
      }
    });
    expect(alert.getAttribute('data-breakpoint')).toBe('medium');

    const expanded = states.get(BREAKPOINT_MEDIA_QUERIES.expanded);
    act(() => {
      if (!expanded) {
        return;
      }
      expanded.matches = true;
      for (const listener of expanded.listeners) {
        listener({ matches: true } as MediaQueryListEvent);
      }
    });
    expect(alert.getAttribute('data-breakpoint')).toBe('expanded');
    expect(screen.getAllByRole('link')).toHaveLength(SYNTHETIC_ERRORS.length);
  });
});
