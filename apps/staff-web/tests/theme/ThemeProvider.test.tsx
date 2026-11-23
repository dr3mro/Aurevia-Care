import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider, useTheme } from '../../src/theme/ThemeProvider';

function Consumer() {
  const { mode, toggleMode } = useTheme();
  return (
    <div>
      <span data-testid="mode">{mode}</span>
      <button type="button" onClick={toggleMode}>
        toggle theme
      </button>
    </div>
  );
}

describe('theme provider', () => {
  it('starts in light mode with the data-theme attribute set', () => {
    render(
      <ThemeProvider>
        <Consumer />
      </ThemeProvider>,
    );
    expect(screen.getByTestId('mode').textContent).toBe('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('toggles to dark and back through an explicit user action', () => {
    render(
      <ThemeProvider>
        <Consumer />
      </ThemeProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'toggle theme' }));
    expect(screen.getByTestId('mode').textContent).toBe('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    fireEvent.click(screen.getByRole('button', { name: 'toggle theme' }));
    expect(screen.getByTestId('mode').textContent).toBe('light');
  });

  it('throws when used outside its provider', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => render(<Consumer />)).toThrow(/useTheme/);
    error.mockRestore();
  });
});
