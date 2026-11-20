import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from '../App';

describe('code-split route screens', () => {
  it('shows one accessible loading fallback then resolves every lazy feature screen', async () => {
    render(<App />);

    const fallback = screen.getByText('Loading');
    expect(fallback.tagName).toBe('P');
    expect(fallback.getAttribute('role')).toBe('status');

    await waitFor(() => expect(screen.queryByText('Loading')).toBeNull());
  });
});
