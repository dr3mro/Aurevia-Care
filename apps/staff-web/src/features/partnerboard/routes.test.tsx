import { render, screen } from '@testing-library/react';
import { Suspense } from 'react';
import { describe, expect, it } from 'vitest';

import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { routes } from './routes';

describe('partner board feature route', () => {
  it('registers one operations route', () => {
    expect(routes).toHaveLength(1);
    expect(required(routes[0]).pattern).toBe('/operations/partner-board');
  });

  it('lazily loads a component that renders the board', async () => {
    const Page = required(routes[0]).component;
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <Suspense fallback={<div>loading board</div>}>
            <Page />
          </Suspense>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(await screen.findByText('Partner interface and outage board')).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
