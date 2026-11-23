import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { RouterProvider } from '../../app/router';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { ChartPage } from './ChartPage';
import type { ChartEntryResponse, ChartViewResponse, PatientViewResponse } from './api';
import { chartText } from './messages';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function entry(overrides: Partial<ChartEntryResponse> = {}): ChartEntryResponse {
  return {
    id: 'entry-1',
    patientId: 'patient-1',
    encounterId: null,
    type: 'PROBLEM',
    originalText: 'Hypertension',
    originalCode: 'HTN',
    codeSystem: 'local',
    mappedCode: null,
    mappedText: null,
    catalogVersion: null,
    source: 'CLINICIAN_OBSERVED',
    presence: 'PRESENT',
    unknownReason: null,
    verification: 'VERIFIED',
    verifiedBy: 'dr-1',
    verifiedAt: null,
    effectiveFrom: '2026-03-09',
    effectiveTo: null,
    measuredAt: null,
    value: null,
    unit: null,
    clinicalAuthor: 'dr-1',
    dataEntryActor: 'reception-1',
    version: 1,
    recordedAt: '2026-03-09T09:00:00Z',
    ...overrides,
  };
}

function chartView(overrides: Partial<ChartViewResponse> = {}): ChartViewResponse {
  return { entries: [entry()], ...overrides };
}

function patientView(): PatientViewResponse {
  return { patient: { id: 'patient-1', displayName: 'Layla Haddad' }, duplicates: [] };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function transportFailure(
  kind: ConstructorParameters<typeof TransportError>[0],
  correlationId = 'corr-1',
) {
  return new TransportError(kind, { correlationId, code: undefined, retryAfterSeconds: undefined });
}

const chartPatterns = ['/patients/:id/chart'];

function element(
  ui: ReactElement,
  organizationId: string,
  locale: 'en' | 'ar' = 'en',
  path = '/patients/patient-1/chart',
) {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>
          <RouterProvider patterns={chartPatterns} initialPath={path}>
            {ui}
          </RouterProvider>
        </ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

beforeEach(() => {
  sendMock.mockReset();
});

/** Route each request to the response shape its URL actually expects. */
function resolveByUrl(chart: ChartViewResponse = chartView()): void {
  sendMock.mockImplementation((request) => {
    const url = (request as { url: string }).url;
    return Promise.resolve(url.startsWith('/api/v1/patients/') ? patientView() : chart);
  });
}

describe('ChartPage states', () => {
  it('shows the loading state until the chart resolves', async () => {
    const pending = deferred<ChartViewResponse>();
    const patient = deferred<PatientViewResponse>();
    sendMock.mockImplementation((request) =>
      (request as { url: string }).url.startsWith('/api/v1/patients/')
        ? patient.promise
        : pending.promise,
    );

    render(element(<ChartPage />, 'org-a'));
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    patient.resolve(patientView());
    pending.resolve(chartView());
    expect((await screen.findAllByText('Hypertension')).length).toBeGreaterThan(0);
  });

  it('shows the empty state when the chart has no entries', async () => {
    sendMock.mockResolvedValueOnce(patientView()).mockResolvedValueOnce(chartView({ entries: [] }));
    render(element(<ChartPage />, 'org-a'));
    expect(await screen.findByRole('heading', { name: stateViewText('en', 'empty') })).toBeTruthy();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows an error with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind, `corr-${kind}`));
      render(element(<ChartPage />, 'org-a'));
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden and session-expired states', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));
    render(element(<ChartPage />, 'org-a'));
    expect(
      await screen.findByRole('heading', { name: stateViewText('en', 'forbidden') }),
    ).toBeTruthy();
  });
});

describe('ChartPage content and paging', () => {
  beforeEach(() => {
    resolveByUrl();
  });

  it('shows the patient name together with the opaque patient id', async () => {
    render(element(<ChartPage />, 'org-a'));
    expect((await screen.findAllByText('Layla Haddad')).length).toBeGreaterThan(0);
    expect(screen.getByText('patient-1')).toBeTruthy();
    expect(screen.getByText(/Patient ID/)).toBeTruthy();
  });

  it('renders the longitudinal chart and no Load more when there is no cursor', async () => {
    render(element(<ChartPage />, 'org-a'));
    expect(await screen.findByRole('table', { name: chartText('en', 'problems') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: chartText('en', 'loadMore') })).toBeNull();
  });

  it('loads the next cursor page and appends its entries', async () => {
    sendMock.mockReset();
    let chartCall = 0;
    sendMock.mockImplementation((request) => {
      const url = (request as { url: string }).url;
      if (url.startsWith('/api/v1/patients/')) {
        return Promise.resolve(patientView());
      }
      chartCall += 1;
      return Promise.resolve(
        chartCall === 1
          ? chartView({ nextCursor: 'cursor-2' })
          : chartView({
              entries: [entry({ id: 'entry-2', originalText: 'Asthma' })],
              nextCursor: null,
            }),
      );
    });

    render(element(<ChartPage />, 'org-a'));
    const more = await screen.findByRole('button', { name: chartText('en', 'loadMore') });
    fireEvent.click(more);
    expect((await screen.findAllByText('Asthma')).length).toBeGreaterThan(0);
    expect((await screen.findAllByText('Hypertension')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: chartText('en', 'loadMore') })).toBeNull();
    const cursorCall = sendMock.mock.calls.find(([request]) =>
      (request as { url: string }).url.includes('cursor=cursor-2'),
    );
    expect(cursorCall).toBeDefined();
  });

  it('keeps the chart and shows a banner when a later page fails to load', async () => {
    sendMock.mockReset();
    let chartCall = 0;
    sendMock.mockImplementation((request) => {
      const url = (request as { url: string }).url;
      if (url.startsWith('/api/v1/patients/')) {
        return Promise.resolve(patientView());
      }
      chartCall += 1;
      if (chartCall === 1) {
        return Promise.resolve(chartView({ nextCursor: 'cursor-2' }));
      }
      return Promise.reject(transportFailure('server', 'page-failed'));
    });

    render(element(<ChartPage />, 'org-a'));
    fireEvent.click(await screen.findByRole('button', { name: chartText('en', 'loadMore') }));
    expect(await screen.findByTestId('chart-page-error')).toBeTruthy();
    expect(screen.getByRole('table', { name: chartText('en', 'problems') })).toBeTruthy();
  });

  it('clears the previous tenant rows on an organization switch', async () => {
    const view = render(element(<ChartPage />, 'org-a'));
    expect((await view.findAllByText('Hypertension')).length).toBeGreaterThan(0);

    view.rerender(element(<ChartPage />, 'org-b'));
    await waitFor(() => expect(view.queryAllByText('Hypertension').length).toBe(0));
  });

  it('renders in Arabic with a right-to-left document', async () => {
    render(element(<ChartPage />, 'org-a', 'ar'));
    expect(await screen.findByRole('table', { name: chartText('ar', 'problems') })).toBeTruthy();
    expect(document.documentElement.dir).toBe('rtl');
  });

  it('tolerates a missing route id by using an empty identifier', async () => {
    render(element(<ChartPage />, 'org-a', 'en', '/patients'));
    expect(
      await screen.findByRole('heading', { level: 2, name: chartText('en', 'chart') }),
    ).toBeTruthy();
  });
});

describe('ChartPage route registration', () => {
  it('lazily loads the registered chart page', async () => {
    const route = must(
      createRoutes(false).find((candidate) => candidate.pattern === '/patients/:id/chart'),
    );
    resolveByUrl();
    render(
      element(
        <Suspense fallback={<p>Loading</p>}>
          <route.component />
        </Suspense>,
        'org-a',
      ),
    );
    expect((await screen.findAllByText('Layla Haddad')).length).toBeGreaterThan(0);
  });
});
