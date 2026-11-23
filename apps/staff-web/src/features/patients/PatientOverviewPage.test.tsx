import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { RouterProvider } from '../../app/router';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import {
  alphaOrganizationId,
  syntheticAlphaPatients,
  syntheticPatientView,
  syntheticPatientViewNoDuplicates,
} from './fixtures';
import { PatientOverviewPage } from './PatientOverviewPage';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);
const overviewPatterns = ['/patients/:id'];

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

function element(
  ui: ReactElement,
  organizationId: string,
  locale: Locale = 'en',
  routing: { withRouter?: boolean; path?: string } = {},
) {
  const { withRouter = true, path = '/patients/patient-a1' } = routing;
  const routed = withRouter ? (
    <RouterProvider patterns={overviewPatterns} initialPath={path}>
      {ui}
    </RouterProvider>
  ) : (
    ui
  );
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{routed}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, organizationId = alphaOrganizationId, locale: Locale = 'en') {
  return render(element(ui, organizationId, locale));
}

function transportFailure(kind: ConstructorParameters<typeof TransportError>[0], code?: string) {
  return new TransportError(kind, {
    correlationId: `corr-${kind}`,
    code,
    retryAfterSeconds: undefined,
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('PatientOverviewPage states', () => {
  it('shows the loading state until the patient view resolves', async () => {
    const pending = deferred<typeof syntheticPatientView>();
    sendMock.mockReturnValueOnce(pending.promise);
    wrap(<PatientOverviewPage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(syntheticPatientView);
    expect(await screen.findByText('Layla Haddad')).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind));
      wrap(<PatientOverviewPage />);
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state when the API answers 403', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    wrap(<PatientOverviewPage />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));
    wrap(<PatientOverviewPage />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });
});

describe('PatientOverviewPage content', () => {
  it('shows the identity and a link to the chart', async () => {
    sendMock.mockResolvedValueOnce(syntheticPatientViewNoDuplicates);
    wrap(<PatientOverviewPage />);

    expect(await screen.findByText('Layla Haddad')).toBeTruthy();
    expect(screen.getByText('MRN-1001')).toBeTruthy();
    expect(screen.getByText('SYNTHETIC')).toBeTruthy();
    expect(screen.getByText('May 12, 1990')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Open chart' }).getAttribute('href')).toBe(
      '/patients/patient-a1/chart',
    );
    const descriptor = descriptorAt(0);
    expect(descriptor.method).toBe('GET');
    expect(descriptor.url).toBe('/api/v1/patients/patient-a1');
    expect(descriptor.headers['X-Active-Organization']).toBe(alphaOrganizationId);
  });

  it('shows a likely-duplicate warning with the candidate identity', async () => {
    sendMock.mockResolvedValueOnce(syntheticPatientView);
    wrap(<PatientOverviewPage />);

    const warning = await screen.findByRole('alert', { name: 'Likely duplicate records' });
    expect(warning.textContent).toContain('ليلى حداد');
    expect(warning.textContent).toContain('MRN-1002');
  });

  it('does not show a warning when the patient has no likely duplicates', async () => {
    sendMock.mockResolvedValueOnce(syntheticPatientViewNoDuplicates);
    wrap(<PatientOverviewPage />);

    expect(await screen.findByText('No likely duplicate records.')).toBeTruthy();
    expect(screen.queryByRole('alert', { name: 'Likely duplicate records' })).toBeNull();
  });

  it('renders the Arabic identity under RTL', async () => {
    const arabicView = {
      patient: required(syntheticAlphaPatients[1]),
      duplicates: [],
    };
    sendMock.mockResolvedValueOnce(arabicView);
    wrap(<PatientOverviewPage />, alphaOrganizationId, 'ar');

    expect(document.documentElement.dir).toBe('rtl');
    const name = await screen.findByText('ليلى حداد');
    expect(name.getAttribute('lang')).toBe('ar');
    expect(await screen.findByRole('link', { name: 'فتح الملف الطبي' })).toBeTruthy();
  });

  it('uses an empty id when no route parameter is bound', async () => {
    sendMock.mockResolvedValueOnce(syntheticPatientViewNoDuplicates);
    render(element(<PatientOverviewPage />, alphaOrganizationId, 'en', { withRouter: false }));

    expect(await screen.findByText('Layla Haddad')).toBeTruthy();
    expect(descriptorAt(0).url).toBe('/api/v1/patients/');
  });

  it('reloads the view for the new tenant when the organization switches', async () => {
    sendMock.mockResolvedValue(syntheticPatientViewNoDuplicates);
    const view = wrap(<PatientOverviewPage />, alphaOrganizationId);
    await screen.findByText('Layla Haddad');

    view.rerender(element(<PatientOverviewPage />, '22222222-2222-2222-2222-222222222222'));
    await screen.findByText('Layla Haddad');
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(
      '22222222-2222-2222-2222-222222222222',
    );
  });
});

describe('patient overview routes', () => {
  it('registers the overview path before the chart and catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/patients/:id');
    expect(patterns.indexOf('/patients/:id')).toBeLessThan(patterns.indexOf('/patients/:id/chart'));
  });

  it('lazily loads the overview page with the bound patient id', async () => {
    sendMock.mockResolvedValueOnce(syntheticPatientViewNoDuplicates);
    const route = required(
      createRoutes(false).find((candidate) => candidate.pattern === '/patients/:id'),
    );
    const Page = route.component;
    render(
      <Suspense fallback={<p>Loading route</p>}>
        {element(<Page />, alphaOrganizationId, 'en', { path: '/patients/patient-a1' })}
      </Suspense>,
    );

    expect(await screen.findByText('Layla Haddad')).toBeTruthy();
    expect(descriptorAt(0).url).toBe('/api/v1/patients/patient-a1');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
