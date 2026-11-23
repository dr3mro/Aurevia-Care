import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { Form } from '../../src/components/Form';
import { FormField } from '../../src/components/FormField';
import { BREAKPOINT_MEDIA_QUERIES } from '../../src/theme/breakpoints';

/** Install a deterministic matchMedia stand-in for the three layout bands. */
function installMatchMedia(band: 'compact' | 'medium' | 'expanded'): void {
  const answers: Record<string, boolean> = {
    [BREAKPOINT_MEDIA_QUERIES.medium]: band !== 'compact',
    [BREAKPOINT_MEDIA_QUERIES.expanded]: band === 'expanded',
  };
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: answers[query] ?? false,
      media: query,
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    })),
  );
}

/** Synthetic fields only: no patient data, no real identifiers. */
function fields(count: number, labels?: string[]): ReactNode[] {
  return Array.from({ length: count }, (_, index) => (
    <FormField
      key={`field-${index}`}
      id={`field-${index}`}
      label={labels?.[index] ?? `Field ${index + 1}`}
      value=""
      onChange={() => undefined}
    />
  ));
}

function styleOf(element: Element | null): string {
  return element?.getAttribute('style') ?? '';
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('responsive Form layout (WEB-07, UX-01)', () => {
  it('stacks fields in one column and pins the submit bar on compact', () => {
    installMatchMedia('compact');
    const onSubmit = vi.fn();
    render(
      <ThemeProvider>
        <Form label="Compact form" submitLabel="Save" onSubmit={onSubmit}>
          {fields(3)}
        </Form>
      </ThemeProvider>,
    );

    const form = screen.getByRole('form', { name: 'Compact form' });
    expect(form.getAttribute('data-breakpoint')).toBe('compact');

    const fieldRegion = form.querySelector('[data-part="fields"]');
    expect(fieldRegion?.className).toBe('stack');
    expect(fieldRegion?.getAttribute('data-layout')).toBe('single-column');

    const actions = form.querySelector('[data-part="actions"]');
    expect(actions?.classList.contains('touch')).toBe(true);
    expect(styleOf(actions)).toContain('position: sticky');
    expect(styleOf(actions)).toContain('inset-block-end');
    // The sticky bar reserves scroll space so it cannot cover a focused field.
    expect(styleOf(form)).toContain('scroll-padding-block-end');
  });

  it('uses two columns from medium only when the form has more than six fields', () => {
    installMatchMedia('medium');
    const { unmount } = render(
      <ThemeProvider>
        <Form label="Wide form" submitLabel="Save" onSubmit={() => undefined}>
          {fields(7)}
        </Form>
      </ThemeProvider>,
    );
    let form = screen.getByRole('form', { name: 'Wide form' });
    expect(form.getAttribute('data-breakpoint')).toBe('medium');
    expect(form.querySelector('[data-part="fields"]')?.className).toBe('grid-2');
    expect(form.querySelector('[data-part="fields"]')?.getAttribute('data-layout')).toBe(
      'two-column',
    );
    // No clinical field is hidden to achieve the two-column layout.
    expect(form.querySelectorAll('input')).toHaveLength(7);
    expect(styleOf(form.querySelector('[data-part="fields"]'))).not.toContain('display: none');
    expect(styleOf(form.querySelector('[data-part="actions"]'))).not.toContain('position: sticky');

    unmount();
    render(
      <ThemeProvider>
        <Form label="Short form" submitLabel="Save" onSubmit={() => undefined}>
          {fields(6)}
        </Form>
      </ThemeProvider>,
    );
    form = screen.getByRole('form', { name: 'Short form' });
    expect(form.querySelector('[data-part="fields"]')?.className).toBe('stack');
  });

  it('keeps the two-column grid and normal action row at expanded', () => {
    installMatchMedia('expanded');
    render(
      <ThemeProvider>
        <Form label="Desktop form" submitLabel="Save" onSubmit={() => undefined}>
          {fields(8)}
        </Form>
      </ThemeProvider>,
    );
    const form = screen.getByRole('form', { name: 'Desktop form' });
    expect(form.getAttribute('data-breakpoint')).toBe('expanded');
    expect(form.querySelector('[data-part="fields"]')?.className).toBe('grid-2');
    expect(styleOf(form.querySelector('[data-part="actions"]'))).not.toContain('position: sticky');
  });

  it('keeps the error summary above the fields and preserves keyboard order', () => {
    installMatchMedia('compact');
    render(
      <ThemeProvider>
        <Form label="Ordered form" submitLabel="Save" onSubmit={() => undefined}>
          <p data-part="summary">Please correct the highlighted field</p>
          {fields(3)}
        </Form>
      </ThemeProvider>,
    );
    const form = screen.getByRole('form', { name: 'Ordered form' });
    const fieldRegion = form.querySelector('[data-part="fields"]');
    expect(fieldRegion?.firstElementChild?.getAttribute('data-part')).toBe('summary');

    for (let index = 0; index < 3; index += 1) {
      expect(screen.getByLabelText(`Field ${index + 1}`)).toBeTruthy();
    }
    const submit = screen.getByRole('button', { name: 'Save' });
    expect(submit.getAttribute('type')).toBe('submit');

    const focusables = [...form.querySelectorAll('input, button')];
    expect(focusables.map((element) => element.tagName)).toEqual([
      'INPUT',
      'INPUT',
      'INPUT',
      'BUTTON',
    ]);
    expect(focusables.at(-1)).toBe(submit);
  });

  it('mirrors with logical-only styles and no direction override under RTL', () => {
    installMatchMedia('compact');
    const arabicLabels = ['اسم المريض', 'ملاحظة سريرية طويلة جدا تحتاج إلى التفاف في شاشة الهاتف'];
    render(
      <div dir="rtl" lang="ar">
        <ThemeProvider>
          <Form label="نموذج" submitLabel="حفظ" onSubmit={() => undefined}>
            {fields(2, arabicLabels)}
          </Form>
        </ThemeProvider>
      </div>,
    );
    const form = screen.getByRole('form', { name: 'نموذج' });
    expect(form.getAttribute('data-breakpoint')).toBe('compact');
    expect(form.querySelector('[data-part="fields"]')?.className).toBe('stack');

    for (const element of [form, form.querySelector('[data-part="actions"]')]) {
      const style = styleOf(element);
      expect(style).not.toMatch(/\b(?:left|right|top|bottom)\s*:/);
      expect(style).not.toContain('direction');
      expect(style).not.toContain('display: none');
    }
    expect(styleOf(form.querySelector('[data-part="actions"]'))).toContain('padding-inline');
    for (const label of arabicLabels) {
      expect(screen.getByLabelText(label)).toBeTruthy();
    }
    expect(screen.getByRole('button', { name: 'حفظ' })).toBeTruthy();
  });

  it('counts fields inside layout wrappers, not just top-level children', () => {
    installMatchMedia('medium');
    render(
      <ThemeProvider>
        <Form label="Wrapped form" submitLabel="Save" onSubmit={() => undefined}>
          <div>{fields(7)}</div>
        </Form>
      </ThemeProvider>,
    );
    const form = screen.getByRole('form', { name: 'Wrapped form' });
    expect(form.querySelector('[data-part="fields"]')?.className).toBe('grid-2');
  });

  it('ignores summaries and prose but counts native controls as fields', () => {
    installMatchMedia('medium');
    render(
      <ThemeProvider>
        <Form label="Mixed form" submitLabel="Save" onSubmit={() => undefined}>
          <p>Check the highlighted field</p>
          <select aria-label="Modality" defaultValue="">
            <option value="">Choose</option>
            <option value="CT">CT</option>
          </select>
          {fields(6)}
        </Form>
      </ThemeProvider>,
    );
    const form = screen.getByRole('form', { name: 'Mixed form' });
    expect(form.querySelector('[data-part="fields"]')?.className).toBe('grid-2');
  });

  it('ignores plain text children and traverses fragments when counting fields', () => {
    installMatchMedia('medium');
    const { unmount } = render(
      <ThemeProvider>
        <Form label="Text form" submitLabel="Save" onSubmit={() => undefined}>
          {'Prose that is not a field'}
          {fields(7)}
        </Form>
      </ThemeProvider>,
    );
    expect(
      screen.getByRole('form', { name: 'Text form' }).querySelector('[data-part="fields"]')
        ?.className,
    ).toBe('grid-2');
    unmount();

    render(
      <ThemeProvider>
        <Form label="Fragment form" submitLabel="Save" onSubmit={() => undefined}>
          <>{fields(7)}</>
        </Form>
      </ThemeProvider>,
    );
    expect(
      screen.getByRole('form', { name: 'Fragment form' }).querySelector('[data-part="fields"]')
        ?.className,
    ).toBe('grid-2');
  });

  it('still calls onSubmit through the normal submit event at every band', () => {
    for (const band of ['compact', 'medium', 'expanded'] as const) {
      installMatchMedia(band);
      const onSubmit = vi.fn();
      const view = render(
        <ThemeProvider>
          <Form label="Submit form" submitLabel="Save" onSubmit={onSubmit}>
            {fields(1)}
          </Form>
        </ThemeProvider>,
      );
      fireEvent.submit(screen.getByRole('form', { name: 'Submit form' }));
      expect(onSubmit).toHaveBeenCalledTimes(1);
      view.unmount();
    }
  });
});
