import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { Button } from '../../src/components/Button';

function renderButton(ui: ReactNode) {
  return render(<ThemeProvider>{ui}</ThemeProvider>);
}

describe('accessible Button', () => {
  it('defaults to a primary button of type button and fires an explicit click', () => {
    const onClick = vi.fn();
    renderButton(<Button onClick={onClick}>Save</Button>);
    const button = screen.getByRole('button', { name: 'Save' });
    expect(button.getAttribute('type')).toBe('button');
    expect(button.getAttribute('data-variant')).toBe('primary');
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('renders the secondary variant without a click handler', () => {
    renderButton(<Button variant="secondary">Cancel</Button>);
    expect(screen.getByRole('button', { name: 'Cancel' }).getAttribute('data-variant')).toBe(
      'secondary',
    );
  });

  it('does not fire when disabled and supports submit forms', () => {
    const onClick = vi.fn();
    renderButton(
      <Button disabled onClick={onClick} type="submit">
        Submit
      </Button>,
    );
    const button = screen.getByRole<HTMLButtonElement>('button', { name: 'Submit' });
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('type')).toBe('submit');
    fireEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });
});
