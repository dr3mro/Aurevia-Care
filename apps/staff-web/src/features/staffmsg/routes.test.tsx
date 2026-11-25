import { render, screen } from '@testing-library/react';
import { Suspense } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { routes } from './routes';

// The route contract is about lazy resolution, not page behaviour. Inert pages
// keep this test independent of tenant fixtures and network mocks in the slices.
vi.mock('./mail/MailPage', () => ({
  MailPage: () => <p>synthetic mail route page</p>,
}));
vi.mock('./directory/DirectoryPage', () => ({
  DirectoryPage: () => <p>synthetic directory route page</p>,
}));

/** Narrow an optional lookup without a non-null assertion. */
function required<T>(value: T | undefined): T {
  if (value === undefined) {
    throw new Error('Expected a registered route');
  }
  return value;
}

describe('staff messaging routes', () => {
  it('declares only the two self-contained page routes', () => {
    expect(routes.map((route) => route.pattern)).toEqual([
      '/messaging/mail',
      '/messaging/directory',
    ]);
  });

  it.each([
    ['/messaging/mail', 'synthetic mail route page'],
    ['/messaging/directory', 'synthetic directory route page'],
  ])('lazily resolves %s to its page', async (pattern, expectedText) => {
    const route = required(routes.find((candidate) => candidate.pattern === pattern));
    const Page = route.component;
    render(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <Suspense fallback={<p>loading route</p>}>
            <Page />
          </Suspense>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(await screen.findByText(expectedText)).toBeTruthy();
  });
});
