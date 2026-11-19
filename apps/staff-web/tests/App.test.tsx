import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { App } from '../src/App';

describe('application entry composition', () => {
  it('mounts the provider tree and the staff shell through the normal entry point', () => {
    render(<App />);
    expect(screen.getByRole('banner').textContent).toContain('Aurevia Care');
    expect(screen.getByRole('main')).toBeTruthy();
    expect(document.documentElement.dir).toBe('ltr');
  });
});
