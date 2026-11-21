import { render, screen } from '@testing-library/react';
import { Suspense } from 'react';
import { describe, expect, it } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { routes } from './routes';

describe('LAB-02 release feature route', () => {
  it('registers one laboratory release route', () => {
    expect(routes).toHaveLength(1);
    expect(routes[0]!.pattern).toBe('/lab/release');
  });

  it('lazily loads a page that renders the comparison and release status', async () => {
    const Page = routes[0]!.component;
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <Suspense fallback={<div>loading release</div>}>
            <Page />
          </Suspense>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(await screen.findByText('Laboratory report release and corrections')).toBeTruthy();
    expect(screen.getByRole('table', { name: /LAB-1042/ })).toBeTruthy();
    expect(screen.getByTestId('release-status-clinician')).toBeTruthy();
  });
});
