import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { FormField } from '../../src/components/FormField';
import { BREAKPOINT_MEDIA_QUERIES } from '../../src/theme/breakpoints';
import { must } from '../must';

type ChangeListener = (event: MediaQueryListEvent) => void;

interface QueryState {
  matches: boolean;
  listeners: Set<ChangeListener>;
}

interface WidthCase {
  width: number;
  breakpoint: 'compact' | 'medium' | 'expanded';
  coarse: boolean;
}

/**
 * A deterministic matchMedia stand-in that answers the two `min-width` layout
 * queries and the coarse-pointer query from a synthetic viewport width, the way
 * a real browser would. jsdom cannot lay out pixels, so the assertions read the
 * observable classes, data attributes and DOM order instead of measured boxes.
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

function renderField(ui: ReactNode) {
  return render(<ThemeProvider>{ui}</ThemeProvider>);
}

const widthCases: WidthCase[] = [
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
});

describe('responsive FormField (WEB-07, UX-01)', () => {
  it.each(widthCases)(
    'keeps one stacked, full-inline field at $width px ($breakpoint)',
    ({ width, breakpoint, coarse }) => {
      installMatchMedia(width, coarse);
      const { container } = renderField(
        <FormField
          id="clinical-note"
          label="Clinical note"
          value="Synthetic draft"
          onChange={() => undefined}
        />,
      );

      const stack = container.firstElementChild as HTMLElement;
      // The same DOM and accessibility tree is kept across breakpoints: a
      // single stacked field with the label above the control, never a hidden
      // clinical field or a direction override.
      expect(stack.classList.contains('stack')).toBe(true);
      expect(stack.getAttribute('data-breakpoint')).toBe(breakpoint);
      expect(stack.getAttribute('data-coarse-pointer')).toBe(String(coarse));

      const input = screen.getByLabelText<HTMLInputElement>('Clinical note');
      expect(input.classList.contains('field')).toBe(true);
      expect(input.classList.contains('touch')).toBe(true);
      expect(input.style.direction).toBe('');
      expect(stack.style.direction).toBe('');
      expect(container.querySelector('[style*="display: none"]')).toBeNull();

      const label = must(container.querySelector<HTMLLabelElement>('label'));
      expect(label.htmlFor).toBe(input.id);
      expect(label.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    },
  );

  it('keeps long English label and error text wrapped, associated and untruncated', () => {
    installMatchMedia(320, true);
    const label = 'A deliberately long English label for a narrow clinical field';
    const error =
      'This is a deliberately long clinical validation message that must wrap across several lines without being truncated or clipped on a narrow phone viewport.';
    renderField(
      <FormField id="long-note" label={label} value="" onChange={() => undefined} error={error} />,
    );

    const input = screen.getByLabelText(label);
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe('long-note-error');
    const alert = screen.getByRole('alert');
    expect(alert.id).toBe('long-note-error');
    expect(alert.textContent).toBe(error);
    // The alert is a direct child of .stack, whose rule supplies
    // min-inline-size: 0 and overflow-wrap: anywhere; no truncation is added.
    expect(alert.parentElement?.classList.contains('stack')).toBe(true);
    expect(alert.style.textOverflow).toBe('');
    expect(alert.style.whiteSpace).toBe('');
  });

  it('mirrors under right-to-left without a direction override or a hidden field', () => {
    installMatchMedia(360, true);
    const label = 'ملاحظة سريرية طويلة يجب أن تلتف دون قص على شاشة الهاتف';
    const error = 'هذه رسالة خطأ عربية طويلة يجب أن تلتف ولا تُقتطع على شاشة الهاتف';
    const { container } = renderField(
      <div dir="rtl" lang="ar">
        <FormField
          id="arabic-note"
          label={label}
          value=""
          onChange={() => undefined}
          error={error}
        />
      </div>,
    );

    const input = screen.getByLabelText<HTMLInputElement>(label);
    expect(input.classList.contains('field')).toBe(true);
    expect(input.classList.contains('touch')).toBe(true);
    expect(screen.getByRole('alert').textContent).toBe(error);
    const stack = must(container.querySelector<HTMLElement>('.stack'));
    expect(stack.getAttribute('data-breakpoint')).toBe('compact');
    expect(stack.style.direction).toBe('');
    expect(input.style.direction).toBe('');
    expect(container.querySelector('[style*="display: none"]')).toBeNull();
  });

  it('keeps a single ordered focusable control bound to its label', () => {
    installMatchMedia(1280, false);
    const { container } = renderField(
      <FormField id="focus-note" label="Focus note" value="" onChange={() => undefined} />,
    );
    const input = screen.getByLabelText<HTMLInputElement>('Focus note');
    const focusable = container.querySelectorAll(
      'input, button, select, textarea, [tabindex]:not([tabindex="-1"])',
    );
    expect(Array.from(focusable)).toEqual([input]);
    expect(input.tabIndex).toBe(0);
    input.focus();
    expect(document.activeElement).toBe(input);
  });

  it('leaves inputmode and autocomplete untouched and preserves type and placeholder', () => {
    installMatchMedia(768, true);
    renderField(
      <FormField
        id="email-field"
        label="Email"
        value=""
        onChange={() => undefined}
        type="email"
        placeholder="name@example.test"
      />,
    );
    const input = screen.getByLabelText<HTMLInputElement>('Email');
    expect(input.getAttribute('inputmode')).toBeNull();
    expect(input.getAttribute('autocomplete')).toBeNull();
    expect(input.type).toBe('email');
    expect(input.placeholder).toBe('name@example.test');
  });

  it('still reports edits through onChange', () => {
    installMatchMedia(320, true);
    const onChange = vi.fn();
    renderField(<FormField id="edit-note" label="Edit note" value="old" onChange={onChange} />);
    fireEvent.change(screen.getByLabelText('Edit note'), { target: { value: 'new' } });
    expect(onChange).toHaveBeenCalledWith('new');
  });
});
