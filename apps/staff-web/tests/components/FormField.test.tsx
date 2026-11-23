import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { FormField } from '../../src/components/FormField';

describe('accessible FormField', () => {
  it('associates the label and reports edits without an error', () => {
    const onChange = vi.fn();
    render(
      <ThemeProvider>
        <FormField id="note" label="Note" value="draft" onChange={onChange} />
      </ThemeProvider>,
    );
    const input = screen.getByLabelText<HTMLInputElement>('Note');
    expect(input.getAttribute('type')).toBe('text');
    expect(input.value).toBe('draft');
    expect(input.getAttribute('aria-invalid')).toBeNull();
    expect(input.getAttribute('aria-describedby')).toBeNull();
    fireEvent.change(input, { target: { value: 'updated' } });
    expect(onChange).toHaveBeenCalledWith('updated');
  });

  it('exposes an error with aria-invalid, aria-describedby and an alert role', () => {
    render(
      <ThemeProvider>
        <FormField
          id="email"
          label="Email"
          value=""
          onChange={() => undefined}
          error="Required"
          type="email"
          placeholder="name@example.test"
        />
      </ThemeProvider>,
    );
    const input = screen.getByLabelText('Email');
    expect(input.getAttribute('type')).toBe('email');
    expect(input.getAttribute('placeholder')).toBe('name@example.test');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe('email-error');
    expect(screen.getByRole('alert').textContent).toBe('Required');
  });
});
