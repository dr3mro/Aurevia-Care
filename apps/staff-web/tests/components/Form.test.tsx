import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { Form } from '../../src/components/Form';
import { FormField } from '../../src/components/FormField';

describe('accessible Form', () => {
  it('submits through an explicit action without navigating', () => {
    const onSubmit = vi.fn();
    render(
      <ThemeProvider>
        <Form label="Shift note form" submitLabel="Save note" onSubmit={onSubmit}>
          <FormField id="note" label="Note" value="draft" onChange={() => undefined} />
        </Form>
      </ThemeProvider>,
    );
    const form = screen.getByRole('form', { name: 'Shift note form' });
    expect(screen.getByLabelText('Note')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Save note' }).getAttribute('type')).toBe('submit');
    fireEvent.submit(form);
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});
