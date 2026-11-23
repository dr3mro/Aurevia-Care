import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { I18nProvider } from '../../src/i18n/I18nProvider';
import { SignInPlaceholder } from '../../src/app/SignInPlaceholder';

describe('sign-in placeholder', () => {
  it('explains that identity tooling handles sign-in without collecting credentials', () => {
    render(
      <I18nProvider>
        <SignInPlaceholder />
      </I18nProvider>,
    );
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeTruthy();
    expect(screen.getByText(/placeholder/i)).toBeTruthy();
    expect(screen.queryByLabelText(/password/i)).toBeNull();
    expect(screen.queryByLabelText(/email/i)).toBeNull();
  });
});
