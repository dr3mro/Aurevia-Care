import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { I18nProvider } from '../../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../../src/theme/ThemeProvider';
import { BookingForm } from '../../../src/features/scheduling/BookingForm';
import { schedulingText } from '../../../src/features/scheduling/messages';
import { BREAKPOINT_MEDIA_QUERIES } from '../../../src/theme/breakpoints';
import type { Locale } from '../../../src/i18n/messages';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

interface WidthCase {
  width: number;
  band: 'compact' | 'medium' | 'expanded';
  coarse: boolean;
}

/**
 * Install a deterministic matchMedia stand-in that answers the two layout
 * `min-width` queries and the coarse-pointer query from a synthetic viewport
 * width, like a real browser would. jsdom cannot lay out pixels, so the
 * assertions read observable classes, data attributes and DOM order.
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
      const state: QueryState = { matches, listeners: new Set<ChangeListener>() };
      states.set(query, state);
      return {
        get matches() {
          return state.matches;
        },
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: ChangeListener) => {
          state.listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: ChangeListener) => {
          state.listeners.delete(listener);
        },
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      };
    }),
  );
  return states;
}

/** Render the form with synthetic data and always-mounted providers. */
function renderForm(locale: Locale = 'en') {
  const onBook = vi.fn();
  const view = render(
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <BookingForm
          appointments={[]}
          organizationId="synthetic-organization-1"
          onBook={onBook}
        />
      </I18nProvider>
    </ThemeProvider>,
  );
  return { ...view, onBook };
}

/** Find the booking field grid owned by this component (not the Form wrapper). */
function bookingFields(form: HTMLElement): HTMLElement {
  const region = form.querySelector('[data-part="booking-fields"]');
  if (!(region instanceof HTMLElement)) {
    throw new Error('missing booking-fields region');
  }
  return region;
}

/** Find the hint/status region that keeps long clinical prose wrapping. */
function bookingHints(form: HTMLElement): HTMLElement {
  const region = form.querySelector('[data-part="booking-hints"]');
  if (!(region instanceof HTMLElement)) {
    throw new Error('missing booking-hints region');
  }
  return region;
}

const widthCases: WidthCase[] = [
  { width: 320, band: 'compact', coarse: true },
  { width: 360, band: 'compact', coarse: true },
  { width: 390, band: 'compact', coarse: true },
  { width: 768, band: 'medium', coarse: true },
  { width: 820, band: 'medium', coarse: true },
  { width: 1024, band: 'medium', coarse: true },
  { width: 1280, band: 'expanded', coarse: false },
];

afterEach(() => {
  vi.unstubAllGlobals();
  document.documentElement.dir = 'ltr';
  document.documentElement.lang = 'en';
});

describe('responsive BookingForm (WEB-07, UX-01)', () => {
  it.each(widthCases)(
    'stacks fields on phones and uses side-by-side columns from medium at $width px ($band)',
    ({ width, band, coarse }) => {
      installMatchMedia(width, coarse);
      renderForm();

      const form = screen.getByRole('form', {
        name: schedulingText('en', 'bookingForm'),
      }) as HTMLElement;
      expect(form.getAttribute('data-breakpoint')).toBe(band);

      const fields = bookingFields(form);
      expect(fields.className).toBe(band === 'compact' ? 'stack' : 'grid-2');
      expect(fields.getAttribute('data-layout')).toBe(
        band === 'compact' ? 'single-column' : 'two-column',
      );
      expect(fields.getAttribute('data-breakpoint')).toBe(band);
      expect(fields.getAttribute('data-coarse-pointer')).toBe(String(coarse));

      // Every clinical field stays in the accessibility tree; none is hidden
      // to achieve the narrower layout.
      expect(form.querySelectorAll('input')).toHaveLength(5);
      expect(form.querySelector('[style*="display: none"]')).toBeNull();
      const hints = bookingHints(form);
      expect(hints.querySelector('[style*="display: none"]')).toBeNull();
      // The hint region spans the full grid at medium/expanded so long prose
      // wraps across the form instead of being squeezed into one column.
      expect(hints.style.gridColumn).toBe('1 / -1');

      // Labels, roles and the tab order stay identical across bands.
      for (const key of ['facilityCode', 'patientId', 'visitType', 'resource', 'start'] as const) {
        expect(screen.getByLabelText(schedulingText('en', key))).toBeTruthy();
      }
      const submit = screen.getByRole('button', { name: schedulingText('en', 'book') });
      expect(submit.getAttribute('type')).toBe('submit');
      const focusables = [...form.querySelectorAll('input, button')];
      expect(focusables).toHaveLength(6);
      expect(focusables.at(-1)).toBe(submit);
    },
  );

  it.each(widthCases)(
    'keeps native 16px, 44px-target controls with no custom picker at $width px ($band, coarse=$coarse)',
    ({ width, coarse }) => {
      installMatchMedia(width, coarse);
      renderForm();

      const inputs = screen.getAllByRole('textbox');
      expect(inputs).toHaveLength(5);
      for (const input of inputs) {
        expect(input.tagName).toBe('INPUT');
        expect(input.classList.contains('field')).toBe(true);
        expect(input.classList.contains('touch')).toBe(true);
      }
      // There is no custom date/time picker widget (and none is added here):
      // the start instant is a native text INPUT at every width and pointer
      // type. This asserts "no custom widget" accurately; it is not a claim
      // that a native datetime-local picker is rendered. See the notes' native
      // picker deviation and task request.
      const start = screen.getByLabelText(schedulingText('en', 'start'));
      expect(start.getAttribute('type')).toBe('text');
      expect(screen.queryByRole('dialog')).toBeNull();
    },
  );

  it('wraps long English hint and conflict text with logical-only styles on a phone', () => {
    installMatchMedia(320, true);
    renderForm();

    const hints = bookingHints(screen.getByRole('form') as HTMLElement);
    expect(hints.classList.contains('stack')).toBe(true);
    for (const paragraph of hints.querySelectorAll('p')) {
      expect(paragraph.style.overflowWrap).toBe('anywhere');
      expect(paragraph.style.whiteSpace).toBe('normal');
      expect(['0', '0px']).toContain(paragraph.style.minInlineSize);
      expect(paragraph.getAttribute('style')).toContain('min-inline-size');
      expect(paragraph.getAttribute('style')).not.toMatch(/\b(?:left|right|top|bottom)\s*:/);
    }

    const status = screen.getByTestId('conflict-status');
    expect(status.getAttribute('role')).toBe('status');
    expect(status.textContent).toBe('');
  });

  it('mirrors Arabic RTL with logical styles, intact labels and no hidden field', () => {
    installMatchMedia(360, true);
    renderForm('ar');

    expect(document.documentElement.dir).toBe('rtl');
    const form = screen.getByRole('form', {
      name: schedulingText('ar', 'bookingForm'),
    }) as HTMLElement;
    const fields = bookingFields(form);
    expect(fields.getAttribute('data-breakpoint')).toBe('compact');
    expect(fields.getAttribute('data-layout')).toBe('single-column');

    for (const key of ['facilityCode', 'patientId', 'visitType', 'resource', 'start'] as const) {
      expect(screen.getByLabelText(schedulingText('ar', key))).toBeTruthy();
    }
    expect(screen.getByRole('button', { name: schedulingText('ar', 'book') })).toBeTruthy();
    expect(screen.getByTestId('conflict-status').textContent).toBe('');

    for (const element of [form, fields, bookingHints(form)]) {
      expect(element.getAttribute('style') ?? '').not.toContain('direction');
      expect(element.getAttribute('style') ?? '').not.toMatch(/\b(?:left|right|top|bottom)\s*:/);
    }
    expect(form.querySelector('[style*="display: none"]')).toBeNull();
  });

  it('keeps the conflict status and submit path working at every band', () => {
    for (const width of [320, 768, 1280]) {
      installMatchMedia(width, width < 1280);
      const { onBook, unmount } = renderForm();
      for (const key of ['facilityCode', 'patientId', 'visitType', 'resource'] as const) {
        fireEvent.change(screen.getByLabelText(schedulingText('en', key)), {
          target: { value: 'synthetic-value' },
        });
      }
      fireEvent.change(screen.getByLabelText(schedulingText('en', 'start')), {
        target: { value: '2026-03-09T07:00:00Z' },
      });
      expect(screen.getByTestId('conflict-status').textContent).toBe('');
      fireEvent.click(screen.getByRole('button', { name: schedulingText('en', 'book') }));
      expect(onBook).toHaveBeenCalledTimes(1);
      expect(onBook).toHaveBeenCalledWith({
        organizationId: 'synthetic-organization-1',
        resourceId: 'synthetic-value',
        scheduledAt: '2026-03-09T07:00:00Z',
        facilityCode: 'synthetic-value',
        patientId: 'synthetic-value',
        visitType: 'synthetic-value',
      });
      unmount();
    }
  });
});
