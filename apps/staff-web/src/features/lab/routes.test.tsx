import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { accessionPage, accessionView, alphaOrganizationId } from './fixtures';
import { labText } from './messages';
import { routes } from './routes';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function wrap(ui: ReactElement): ReactElement {
  return (
    <Suspense fallback={<p>Loading page</p>}>
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <ActiveOrganizationProvider value={{ organizationId: alphaOrganizationId }}>
            {ui}
          </ActiveOrganizationProvider>
        </I18nProvider>
      </ThemeProvider>
    </Suspense>
  );
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('lab feature routes', () => {
  it('registers the list and detail patterns', () => {
    expect(routes.map((route) => route.pattern)).toEqual([
      '/lab/accessions',
      '/lab/accessions/:id',
    ]);
  });

  it('resolves the lazy page component for the accession list', async () => {
    sendMock.mockResolvedValue(accessionPage());
    const Page = routes[0]!.component;

    render(wrap(<Page />));

    expect(await screen.findByRole('heading', { name: labText('en', 'accessions') })).toBeTruthy();
    const descriptor = sendMock.mock.calls[0]![0] as RequestDescriptor;
    expect(descriptor.url).toBe('/api/v1/lab/accessions');
  });

  it('resolves the lazy page component for the accession detail', async () => {
    sendMock.mockResolvedValue(accessionView());
    const Page = routes[1]!.component;

    render(wrap(<Page />));

    expect(await screen.findByTestId('accession-state')).toBeTruthy();
    const descriptor = sendMock.mock.calls[0]![0] as RequestDescriptor;
    expect(descriptor.url).toBe('/api/v1/lab/accessions/');
  });
});
