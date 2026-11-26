import { Suspense } from 'react';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActiveOrganizationProvider } from '../../api/activeOrganization';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type * as TransportModule from '../../api/transport';
import { stateViewText } from '../../components/stateViewMessages';
import { I18nProvider } from '../../i18n/I18nProvider';
import { ThemeProvider } from '../../theme/ThemeProvider';
import { createRoutes } from '../../app/routes';
import { SchedulingPage } from './SchedulingPage';
import type { AppointmentViewResponse, AppointmentPageResponse } from './api';
import { alphaOrganizationId, betaOrganizationId } from './fixtures';

vi.mock('../../api/transport', async (importOriginal) => {
  const actual = await importOriginal<typeof TransportModule>();
  return { ...actual, send: vi.fn() };
});

const sendMock = vi.mocked(send);

function apiView(
  overrides: Partial<AppointmentViewResponse['appointment']> = {},
): AppointmentViewResponse {
  return {
    appointment: {
      id: 'apt-001',
      version: 3,
      patientId: 'patient-001',
      facilityCode: 'MAIN',
      clinicianCode: 'clinician-7',
      roomCode: null,
      visitType: 'follow-up',
      startsAt: '2026-03-09T09:00:00Z',
      endsAt: '2026-03-09T09:30:00Z',
      state: 'booked',
      seriesId: null,
      expiresAt: null,
      overrideReason: null,
      ...overrides,
    },
    history: [],
  };
}

function page(overrides: Record<string, unknown> = {}): ReactElement {
  return <SchedulingPage anchorCivilDate="2026-03-09" {...overrides} />;
}

function element(ui: ReactElement, organizationId: string): ReactElement {
  return (
    <ThemeProvider>
      <I18nProvider initialLocale="en">
        <ActiveOrganizationProvider value={{ organizationId }}>{ui}</ActiveOrganizationProvider>
      </I18nProvider>
    </ThemeProvider>
  );
}

function wrap(ui: ReactElement, organizationId = alphaOrganizationId) {
  return render(element(ui, organizationId));
}

function descriptorAt(call: number): RequestDescriptor {
  return required(sendMock.mock.calls[call])[0];
}

/** Enter the clinical context the real booking command requires. */
function fillBookingFields() {
  fireEvent.change(screen.getByLabelText('Facility code'), { target: { value: 'MAIN' } });
  fireEvent.change(screen.getByLabelText('Patient identifier'), {
    target: { value: 'synthetic-patient-001' },
  });
  fireEvent.change(screen.getByLabelText('Visit type'), { target: { value: 'follow-up' } });
  fireEvent.change(screen.getByLabelText('Protected resource'), { target: { value: 'room-9' } });
  fireEvent.change(screen.getByLabelText('Start instant (UTC ISO)'), {
    target: { value: '2026-03-09T07:00:00Z' },
  });
}

function transportFailure(
  kind: ConstructorParameters<typeof TransportError>[0],
  correlationId = 'corr-1',
) {
  return new TransportError(kind, {
    correlationId,
    code: undefined,
    retryAfterSeconds: undefined,
  });
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

beforeEach(() => {
  sendMock.mockReset();
});

describe('SchedulingPage states', () => {
  it('shows the loading state until the first page resolves', async () => {
    const pending = deferred<AppointmentPageResponse>();
    sendMock.mockReturnValueOnce(pending.promise);

    wrap(page());
    expect(screen.getByRole('heading', { name: stateViewText('en', 'loading') })).toBeTruthy();

    pending.resolve({ items: [apiView()], nextCursor: null });
    expect(await screen.findByText('patient-001')).toBeTruthy();
  });

  it('shows the empty state with the worklist controls for an empty first page', async () => {
    sendMock.mockResolvedValueOnce({ items: [], nextCursor: null });

    wrap(page());
    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    // The empty message is no longer a replacement for the screen: the screen
    // owns the range controls and the first-booking form. No patient row may
    // leak into an empty page, but the worklist shell itself is expected.
    expect(screen.queryByRole('rowheader')).toBeNull();
    expect(screen.getByRole('button', { name: 'Week' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Book' })).toBeTruthy();
  });

  it('keeps the controls on an empty page, refetches the week and books from it', async () => {
    sendMock.mockResolvedValue({ items: [], nextCursor: null });

    wrap(page());

    expect(await screen.findByText(stateViewText('en', 'empty'))).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Week' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Book' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Week' }));
    await waitFor(() =>
      expect(
        sendMock.mock.calls.some((call) =>
          call[0].url.includes('to=2026-03-16T00%3A00%3A00Z'),
        ),
      ).toBe(true),
    );
    // Changing range clears the accumulated page, so the retained loading
    // guard briefly unmounts the screen. Wait for the form to come back before
    // entering the first booking.
    await screen.findByRole('button', { name: 'Book' });

    fillBookingFields();
    fireEvent.click(screen.getByRole('button', { name: 'Book' }));

    await waitFor(() =>
      expect(
        sendMock.mock.calls.some(
          (call) => call[0].method === 'POST' && call[0].url === '/api/v1/appointments',
        ),
      ).toBe(true),
    );
    const booking = required(
      sendMock.mock.calls.find(
        (call) => call[0].method === 'POST' && call[0].url === '/api/v1/appointments',
      ),
    )[0];
    expect(booking.body).toMatchObject({
      clinicianCode: 'room-9',
      startsAt: '2026-03-09T07:00:00Z',
    });

    // A successful command must reload the active range, not reuse the first page.
    const postIndex = sendMock.mock.calls.findIndex(
      (call) => call[0].method === 'POST' && call[0].url === '/api/v1/appointments',
    );
    await waitFor(() =>
      expect(
        sendMock.mock.calls
          .slice(postIndex + 1)
          .some(
            (call) =>
              call[0].method === 'GET' &&
              call[0].url.includes('to=2026-03-16T00%3A00%3A00Z'),
          ),
      ).toBe(true),
    );
  });

  it.each(['server', 'timeout', 'offline', 'invalid', 'rate-limited'] as const)(
    'shows the error state with a correlation id for %s',
    async (kind) => {
      sendMock.mockRejectedValueOnce(transportFailure(kind, `corr-${kind}`));

      wrap(page());
      expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
      expect(screen.getByText(`corr-${kind}`)).toBeTruthy();
    },
  );

  it('shows the forbidden state for a forbidden list response', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('forbidden'));

    wrap(page());
    expect(await screen.findByText(stateViewText('en', 'forbidden'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'forbiddenDetail'))).toBeTruthy();
  });

  it('shows the session-expired state for an expired session', async () => {
    sendMock.mockRejectedValueOnce(transportFailure('session-expired'));

    wrap(page());
    expect(await screen.findByText(stateViewText('en', 'sessionExpired'))).toBeTruthy();
  });
});

describe('SchedulingPage commands', () => {
  beforeEach(() => {
    sendMock.mockResolvedValue({ items: [apiView()], nextCursor: null });
  });

  it('checks in through the API and reloads the list after success', async () => {
    wrap(page());
    const row = required(
      (await screen.findByRole('rowheader', { name: 'patient-001' })).closest('tr'),
    );

    fireEvent.click(within(row).getByRole('button', { name: 'Check in' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/appointments/apt-001/check-in');
    expect(command.headers['If-Match']).toBe('3');
    expect(descriptorAt(2).method).toBe('GET');
  });

  it('shows the conflict state and keeps the screen for a 409 or 412 command', async () => {
    wrap(page());
    const row = required(
      (await screen.findByRole('rowheader', { name: 'patient-001' })).closest('tr'),
    );
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(within(row).getByRole('button', { name: 'Check in' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByRole('table')).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('validates the booking form before sending any command', async () => {
    wrap(page());
    await screen.findByText('patient-001');

    fireEvent.click(screen.getByRole('button', { name: 'Book' }));
    expect(sendMock).toHaveBeenCalledTimes(1);
    expect(descriptorAt(0).method).toBe('GET');
  });

  it('books through the API and reloads after a valid booking', async () => {
    wrap(page());
    await screen.findByText('patient-001');
    // A populated day must not also present the empty notice.
    expect(screen.queryByText(stateViewText('en', 'empty'))).toBeNull();

    fillBookingFields();
    fireEvent.click(screen.getByRole('button', { name: 'Book' }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(3));
    const command = descriptorAt(1);
    expect(command.method).toBe('POST');
    expect(command.url).toBe('/api/v1/appointments');
    expect(command.body).toMatchObject({
      clinicianCode: 'room-9',
      startsAt: '2026-03-09T07:00:00Z',
    });
    expect(descriptorAt(2).method).toBe('GET');
  });

  it('shows the conflict state for a rejected booking without losing the form', async () => {
    wrap(page());
    await screen.findByText('patient-001');
    fillBookingFields();
    sendMock.mockRejectedValueOnce(transportFailure('conflict'));

    fireEvent.click(screen.getByRole('button', { name: 'Book' }));

    expect(await screen.findByText(stateViewText('en', 'conflict'))).toBeTruthy();
    expect(screen.getByLabelText('Patient identifier')).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(2);
  });

  it('normalises an unexpected command rejection into a typed error state', async () => {
    wrap(page());
    const row = required(
      (await screen.findByRole('rowheader', { name: 'patient-001' })).closest('tr'),
    );
    sendMock.mockRejectedValueOnce(new Error('unexpected transport failure'));

    fireEvent.click(within(row).getByRole('button', { name: 'Check in' }));

    expect(await screen.findByText(stateViewText('en', 'error'))).toBeTruthy();
    expect(screen.getByText(stateViewText('en', 'correlationUnavailable'))).toBeTruthy();
  });

  it('assigns locally without a network command because no assign endpoint exists', async () => {
    wrap(page());
    const row = required(
      (await screen.findByRole('rowheader', { name: 'patient-001' })).closest('tr'),
    );
    // Let the page's page-accumulation effect settle before the local draft.
    await act(async () => {
      await Promise.resolve();
    });

    fireEvent.change(within(row).getByLabelText('Assign to'), {
      target: { value: 'synthetic.nurse' },
    });

    expect(within(row).getByText('synthetic.nurse')).toBeTruthy();
    expect(sendMock).toHaveBeenCalledTimes(1);
  });
});

describe('SchedulingPage range, paging and tenant switching', () => {
  it('queries the day window for the supplied anchor and the week window on demand', async () => {
    sendMock.mockResolvedValue({ items: [apiView()], nextCursor: null });

    wrap(page());
    await screen.findByText('patient-001');
    expect(descriptorAt(0).url).toContain('to=2026-03-10T00%3A00%3A00Z');

    fireEvent.click(screen.getByRole('button', { name: 'Week' }));
    await waitFor(() =>
      expect(
        sendMock.mock.calls.some((call) => call[0].url.includes('to=2026-03-16T00%3A00%3A00Z')),
      ).toBe(true),
    );
  });

  it('anchors the week on the preceding Monday for a Sunday date', async () => {
    sendMock.mockResolvedValue({ items: [apiView()], nextCursor: null });

    wrap(<SchedulingPage anchorCivilDate="2026-03-15" />);
    await screen.findByText('patient-001');
    fireEvent.click(screen.getByRole('button', { name: 'Week' }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));

    const url = descriptorAt(1).url;
    expect(url).toContain('from=2026-03-09T00%3A00%3A00Z');
    expect(url).toContain('to=2026-03-16T00%3A00%3A00Z');
  });

  it('defaults the anchor to the current UTC civil date', async () => {
    sendMock.mockResolvedValue({ items: [], nextCursor: null });

    wrap(<SchedulingPage />);
    await waitFor(() => expect(sendMock).toHaveBeenCalled());

    const today = new Date().toISOString().slice(0, 10);
    expect(descriptorAt(0).url).toContain(`from=${encodeURIComponent(`${today}T00:00:00Z`)}`);
  });

  it('loads the next page while a cursor exists and keeps earlier rows', async () => {
    sendMock.mockResolvedValueOnce({
      items: [apiView({ id: 'apt-001', patientId: 'patient-001' })],
      nextCursor: 'cursor-2',
    });
    sendMock.mockResolvedValueOnce({
      items: [apiView({ id: 'apt-002', patientId: 'patient-002' })],
      nextCursor: null,
    });

    wrap(page());
    expect(await screen.findByText('patient-001')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('patient-002')).toBeTruthy();
    expect(screen.getByText('patient-001')).toBeTruthy();
    expect(descriptorAt(1).url).toContain('cursor=cursor-2');
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('clears the previous tenant rows before the new tenant responds', async () => {
    sendMock.mockResolvedValueOnce({ items: [apiView()], nextCursor: null });
    sendMock.mockResolvedValue({ items: [], nextCursor: null });

    const { rerender } = wrap(page(), alphaOrganizationId);
    expect(await screen.findByText('patient-001')).toBeTruthy();
    expect(descriptorAt(0).headers['X-Active-Organization']).toBe(alphaOrganizationId);

    rerender(element(page(), betaOrganizationId));
    expect(screen.queryByText('patient-001')).toBeNull();
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(2));
    expect(descriptorAt(1).headers['X-Active-Organization']).toBe(betaOrganizationId);
  });
});

describe('scheduling routes', () => {
  it('registers both scheduling paths before the catch-all', () => {
    const patterns = createRoutes(false).map((route) => route.pattern);
    expect(patterns).toContain('/schedule');
    expect(patterns).toContain('/schedule/book');
    expect(patterns.indexOf('/schedule')).toBeLessThan(patterns.indexOf('*'));
  });

  it('lazily loads the registered scheduling page with live data', async () => {
    sendMock.mockResolvedValue({ items: [apiView()], nextCursor: null });
    const route = required(
      createRoutes(false).find((candidate) => candidate.pattern === '/schedule'),
    );
    const Page = route.component;

    render(
      <Suspense fallback={<p>Loading route</p>}>{element(<Page />, alphaOrganizationId)}</Suspense>,
    );

    expect(await screen.findByText('patient-001')).toBeTruthy();
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
