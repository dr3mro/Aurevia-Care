import { render, screen } from '@testing-library/react';
import { Suspense } from 'react';
import { describe, expect, it } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { routes } from './routes';

describe('LAB-02 result-entry feature route', () => {
  it('registers one laboratory results route', () => {
    expect(routes).toHaveLength(1);
    expect(required(routes[0]).pattern).toBe('/lab/results/entry');
  });

  it('lazily loads a page that renders the entry grid and verification summary', async () => {
    const Page = required(routes[0]).component;
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <Suspense fallback={<div>loading results</div>}>
            <Page />
          </Suspense>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(await screen.findByText('Laboratory result entry and verification')).toBeTruthy();
    expect(screen.getByRole('table', { name: /LAB-1042/ })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Verification and release summary' })).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
