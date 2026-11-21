import { render, screen } from '@testing-library/react';
import { Suspense } from 'react';
import { describe, expect, it } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { routes } from './routes';

describe('lab board feature route', () => {
  it('registers one laboratory route', () => {
    expect(routes).toHaveLength(1);
    expect(routes[0]!.pattern).toBe('/lab/turnaround-board');
  });

  it('lazily loads a component that renders the board', async () => {
    const Page = routes[0]!.component;
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <Suspense fallback={<div>loading board</div>}>
            <Page />
          </Suspense>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(await screen.findByText('Laboratory turnaround board')).toBeTruthy();
  });
});
