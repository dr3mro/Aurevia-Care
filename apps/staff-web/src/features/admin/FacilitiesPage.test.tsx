import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { createRoutes } from '../../app/routes';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { ThemeProvider } from '../../theme/ThemeProvider';
import type { FacilityPageResponse } from './api';
import { alphaOrganizationId, betaOrganizationId } from './fixtures';
import { FacilitiesPage } from './FacilitiesPage';
import { must } from '../../../tests/must';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function facilityPage(
  items: FacilityPageResponse['items'] = [],
  nextCursor: string | null = null,
): FacilityPageResponse {
  return { items, nextCursor };
}

function facility(overrides: Partial<FacilityPageResponse['items'][number]> = {}) {
  return {
    organizationId: alphaOrganizationId,
    code: 'MAIN',
    name: 'Synthetic Main Facility',
    timeZone: 'UTC',
    ...overrides,
  };
}

function descriptorAt(call: number): RequestDescriptor {
  return must(sendMock.mock.calls[call])[0];
}

function element(ui: ReactElement, organizationId: string, locale: Locale = 'en') {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale={locale}>
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
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

function fillCreateForm() {
  fireEvent.change(screen.getByLabelText('Facility code'), { target: { value: 'LAB' } });
  fireEvent.change(screen.getByLabelText('Facility name'), { target: { value: 'Synthetic Lab' } });
}

beforeEach(() => {
  sendMock.mockReset();
});

describe('FacilitiesPage states', () => {
  it('shows the loading state until the first page resolves', async () => {
    const pending = deferred<FacilityPageResponse>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap(<FacilitiesPage />);
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve(facilityPage([facility()]));
    expect(await screen.findByText('Synthetic Main Facility')).toBeTruthy();
  });

  it('shows the empty state for an empty first page', async () => {
    sendMock.mockResolvedValueOnce(facilityPage());

    wrap(<FacilitiesPage />);
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind));

      wrap(<FacilitiesPage />);
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state when the API answers 403', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));

    wrap(<FacilitiesPage />);
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'forbiddenDetail'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));

    wrap(<FacilitiesPage />);
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });

  it('labels an omitted facility time zone as UTC', async () => {
    sendMock.mockResolvedValueOnce(facilityPage([facility({ code: 'MAIN', timeZone: null })]));

    wrap(<FacilitiesPage />);
    expect(await screen.findByRole('rowheader', { name: 'MAIN' })).toBeTruthy();
    expect(screen.getByText('UTC')).toBeTruthy();
  });

  it('renders the table and Arabic labels under RTL', async () => {
    sendMock.mockResolvedValueOnce(facilityPage([facility()]));

    wrap(<FacilitiesPage />, alphaOrganizationId, 'ar');
    expect(document.documentElement.dir).toBe('rtl');
    expect(await screen.findByRole('table', { name: 'المرافق' })).toBeTruthy();
  });
});

describe('FacilitiesPage commands', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue(facilityPage([facility()]));
  });

  it('creates a facility through the API and reloads the list after success', async () => {
    wrap(<FacilitiesPage />);
    await screen.findByText('Synthetic Main Facility');
    fillCreateForm();
    sendMock.mockResolvedValueOnce(
      facility({ code: 'LAB', name: 'Synthetic Lab', timeZone: 'UTC' }),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Create facility' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/facilities');
    expect(command.body).toEqual({ code: 'LAB', name: 'Synthetic Lab' });
    expect(command.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(descriptorAt(2).method).toBe('GET');
    expect(await screen.findByText('Created facility Synthetic Lab')).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>('Facility code').value).toBe('');
  });

  it('shows a linked error summary and sends no command for an invalid draft', async () => {
    wrap(<FacilitiesPage />);
    await screen.findByText('Synthetic Main Facility');

    fireEvent.click(screen.getByRole('button', { name: 'Create facility' }));

    expect(sendMock).toHaveBeenCalledTimes(1);
    const summary = screen.getByRole('alert', { name: 'Fix the following fields' });
    expect(summary.querySelector('a[href="#facility-code"]')).toBeTruthy();
    expect(summary.querySelector('a[href="#facility-name"]')).toBeTruthy();
    expect(screen.getByLabelText('Facility code').getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByLabelText('Facility name').getAttribute('aria-invalid')).toBe('true');
  });

  it('links a server code error to the code field and keeps the typed name', async () => {
    wrap(<FacilitiesPage />);
    await screen.findByText('Synthetic Main Facility');
    fillCreateForm();
    sendMock.mockRejectedValueOnce(transportFailure('invalid', 'INVALID_CODE'));

    fireEvent.click(screen.getByRole('button', { name: 'Create facility' }));

    expect(await screen.findByRole('alert', { name: 'Fix the following fields' })).toBeTruthy();
    expect(screen.getByLabelText('Facility code').getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByLabelText('Facility name').getAttribute('aria-invalid')).toBeNull();
    expect(screen.getByLabelText<HTMLInputElement>('Facility name').value).toBe('Synthetic Lab');
    expect(screen.queryByText(stateViewText('en', 'error'))).toBeNull();
  });

  it('falls back to the error state for an unmapped server validation code', async () => {
    wrap(<FacilitiesPage />);
    await screen.findByText('Synthetic Main Facility');
    fillCreateForm();
    sendMock.mockRejectedValueOnce(transportFailure('invalid', 'UNKNOWN_CODE'));

    fireEvent.click(screen.getByRole('button', { name: 'Create facility' }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText('corr-invalid')).toBeTruthy();
    expect(screen.queryByRole('alert', { name: 'Fix the following fields' })).toBeNull();
  });

  it('shows the conflict state for a 409 create without losing the form', async () => {
    wrap(<FacilitiesPage />);
    await screen.findByText('Synthetic Main Facility');
    fillCreateForm();
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(screen.getByRole('button', { name: 'Create facility' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByRole('table')).toBeTruthy();
    expect(screen.getByLabelText<HTMLInputElement>('Facility name').value).toBe('Synthetic Lab');
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('normalizes an unexpected command rejection into a typed error state', async () => {
    wrap(<FacilitiesPage />);
    await screen.findByText('Synthetic Main Facility');
    fillCreateForm();
    sendMock.mockRejectedValueOnce(new Error('unexpected transport failure'));

    fireEvent.click(screen.getByRole('button', { name: 'Create facility' }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('activates the primary action with a native form submit (keyboard equivalent)', async () => {
    wrap(<FacilitiesPage />);
    await screen.findByText('Synthetic Main Facility');
    fillCreateForm();

    const submit = screen.getByRole<HTMLButtonElement>('button', { name: 'Create facility' });
    expect(submit.type).toBe('submit');
    fireEvent.submit(must(submit.closest<HTMLElement>('form')));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
  });
});

describe('FacilitiesPage paging and tenant switching', () => {
  it('appends the next page while a cursor exists', async () => {
    sendMock.mockResolvedValueOnce(facilityPage([facility({ code: 'MAIN' })], 'cursor-2'));
    sendMock.mockResolvedValueOnce(
      facilityPage([facility({ code: 'LAB', name: 'Synthetic Lab' })]),
    );

    wrap(<FacilitiesPage />);
    expect(await screen.findByText('Synthetic Main Facility')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('Synthetic Lab')).toBeTruthy();
    expect(screen.getByText('Synthetic Main Facility')).toBeTruthy();
    expect(descriptorAt(1).url).toContain('cursor=cursor-2');
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('clears the previous tenant rows before the new tenant responds', async () => {
    sendMock.mockResolvedValueOnce(facilityPage([facility()]));
    sendMock.mockResolvedValue(facilityPage());

    const view = wrap(<FacilitiesPage />, alphaOrganizationId);
    expect(await screen.findByText('Synthetic Main Facility')).toBeTruthy();
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    view.rerender(element(<FacilitiesPage />, betaOrganizationId));
    expect(screen.queryByText('Synthetic Main Facility')).toBeNull();
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });
});

describe('admin facilities routes', () => {
  it('registers the facilities path before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/admin/facilities');
    expect(patterns.indexOf('/admin/facilities')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the registered facilities page with live data', async () => {
    sendMock.mockResolvedValue(facilityPage([facility()]));
    const route = must(
      createRoutes(false).find((candidate) => candidate.pattern === '/admin/facilities'),
    );
    const Page = route.component;

    render(
      <Suspense fallback={<p>Loading route</p>}>{element(<Page />, alphaOrganizationId)}</Suspense>,
    );

    expect(await screen.findByText('Synthetic Main Facility')).toBeTruthy();
    expect(within(screen.getByRole('table')).getByText('MAIN')).toBeTruthy();
  });
});
