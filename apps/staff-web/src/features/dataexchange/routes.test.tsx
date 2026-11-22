import { Suspense } from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { routes } from './routes';

/** Synthetic organization used only to mount the lazily loaded routes. */
const organizationId = '11111111-1111-1111-1111-111111111111';

describe('tenant data exchange feature routes', () => {
  it('registers one export route and one import route', () => {
    expect(routes.map((route) => route.pattern)).toEqual([
      '/operations/exports',
      '/operations/imports',
    ]);
  });

  it('lazily loads both screens and conceals them from the synthetic session role', async () => {
    for (const route of routes) {
      const Page = route.component;
      const view = render(
        <ThemeProvider>
          <I18nProvider initialLocale="en">
            <ActiveOrganizationProvider value={{ organizationId }}>
              <Suspense fallback={<p>Loading route</p>}>
                <Page />
              </Suspense>
            </ActiveOrganizationProvider>
          </I18nProvider>
        </ThemeProvider>,
      );
      expect(await screen.findByRole('heading', { name: stateViewText('en', 'forbidden') })).toBeTruthy();
      view.unmount();
    }
  });
});
