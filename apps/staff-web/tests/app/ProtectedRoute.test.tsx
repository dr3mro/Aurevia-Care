import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { I18nProvider } from '../../src/i18n/I18nProvider';
import { ProtectedRoute } from '../../src/app/ProtectedRoute';

describe('protected route placeholder', () => {
  it('conceals the protected content until an explicit flag is set', () => {
    render(
      <I18nProvider>
        <ProtectedRoute>
          <span>restricted content</span>
        </ProtectedRoute>
      </I18nProvider>,
    );
    expect(screen.queryByText('restricted content')).toBeNull();
    expect(screen.getByRole('status').textContent).toMatch(/restricted/i);
  });

  it('renders children when explicitly allowed', () => {
    render(
      <I18nProvider>
        <ProtectedRoute signedIn>
          <span>restricted content</span>
        </ProtectedRoute>
      </I18nProvider>,
    );
    expect(screen.getByText('restricted content')).toBeTruthy();
  });
});
