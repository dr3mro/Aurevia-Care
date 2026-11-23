import { render, screen } from '@testing-library/react';
import { Suspense } from 'react';
import { describe, expect, it } from 'vitest';

import { navigation, canAccessRoute, routeAccessRoles } from '../../app/navigation';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { routes } from './routes';

/** Read a required element without an unsafe non-null assertion. */
function required<T>(value: T | undefined): T {
  if (value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}

describe('results feature routes', () => {
  it('registers both results patterns exactly once', () => {
    const patterns = routes.map((route) => route.pattern);
    expect(patterns).toEqual(['/results', '/results/critical']);
    expect(new Set(patterns).size).toBe(2);
  });

  it('matches the navigation entries and their role gates', () => {
    for (const pattern of routes.map((route) => route.pattern)) {
      expect(navigation.filter((item) => item.route === pattern)).toHaveLength(1);
    }
    expect(routeAccessRoles('/results/critical')).toEqual(['clinician']);
    expect(canAccessRoute('operator', '/results/critical')).toBe(false);
    expect(canAccessRoute('clinician', '/results')).toBe(true);
  });

  it('lazily loads a page that renders the results screen', async () => {
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
    expect(await screen.findByRole('heading', { name: 'Results' })).toBeTruthy();
  });
});
