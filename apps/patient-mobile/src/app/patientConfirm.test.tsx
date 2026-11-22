/**
 * PAT-01 acceptance: pressing Confirm on the installed, signed-in appointments
 * page issues one versioned command and renders the server-recorded timestamp.
 * Every payload below is synthetic; no credential or real patient data appears.
 */
import { jest } from '@jest/globals';
import { fireEvent, render, waitFor } from '@testing-library/react-native';

import { TransportError } from '../api/transport';
import type { Transport } from '../api/transport';

import { AppointmentListPage } from './patientPages';

/** The exact wire shape the patient appointment endpoint returns. */
const eligible = {
  id: 'apt-1',
  version: 3,
  patientId: 'synthetic-patient',
  facilityCode: 'synthetic-facility',
  facilityTimeZone: 'Asia/Riyadh',
  startsAt: '2026-11-21T10:00:00Z',
  status: 'booked',
  confirmationEnabled: true,
  confirmedAt: null,
};

/** Server reply to a successful confirmation (version advanced, instant recorded). */
const confirmedReply = {
  ...eligible,
  version: 4,
  confirmedAt: '2026-11-21T10:05:00Z',
};

interface PageTransport {
  readonly transport: Transport;
  readonly get: jest.Mock<() => Promise<unknown>>;
  readonly post: jest.Mock<(path: string, body?: unknown) => Promise<unknown>>;
}

/** Build a transport with controllable reads and writes for one page render. */
function pageTransport(get: () => Promise<unknown>, post: () => Promise<unknown>): PageTransport {
  const getMock = jest.fn(get) as jest.Mock<() => Promise<unknown>>;
  const postMock = jest.fn(post) as jest.Mock<(path: string, body?: unknown) => Promise<unknown>>;
  return {
    transport: {
      get: getMock as Transport['get'],
      post: postMock as Transport['post'],
      put: jest.fn(async () => undefined) as Transport['put'],
    },
    get: getMock,
    post: postMock,
  };
}

/** The query object returned once the installed page has rendered. */
type Rendered = Awaited<ReturnType<typeof render>>;

/** Render the installed appointments tab with a synthetic signed-in session. */
async function renderPage(
  page: PageTransport,
  onSessionExpired: () => void = () => undefined,
): Promise<Rendered> {
  return render(
    <AppointmentListPage
      transport={page.transport}
      locale="en"
      organizationId="synthetic-organization"
      patientId="synthetic-patient"
      onSessionExpired={onSessionExpired}
    />,
  );
}

/** Wait until the eligible confirmation control is present. */
async function readyConfirm(view: Rendered) {
  await waitFor(() => expect(view.getByTestId('confirm-apt-1')).toBeTruthy());
  return view.getByTestId('confirm-apt-1');
}

describe('patient appointment confirmation through the installed page', () => {
  it('sends the version read from the row and renders the server-confirmed instant', async () => {
    const page = pageTransport(async () => [eligible], async () => confirmedReply);
    const view = await renderPage(page);
    const confirm = await readyConfirm(view);

    await fireEvent.press(confirm);

    await waitFor(() =>
      expect(page.post).toHaveBeenCalledWith('/api/v1/patient/appointments/apt-1/confirm', {
        patientId: 'synthetic-patient',
        expectedVersion: 3,
      }),
    );
    await waitFor(() =>
      expect(view.getByTestId('confirmed-apt-1')).toHaveTextContent(/13:05/),
    );
    expect(view.queryByTestId('confirm-apt-1')).toBeNull();
  });

  it('sends only one request for repeated presses while the first is pending', async () => {
    const page = pageTransport(async () => [eligible], () => new Promise<unknown>(() => undefined));
    const view = await renderPage(page);
    const confirm = await readyConfirm(view);

    await fireEvent.press(confirm);
    await fireEvent.press(view.getByTestId('confirm-apt-1'));

    expect(page.post).toHaveBeenCalledTimes(1);
    expect(view.getByTestId('confirm-apt-1').props.accessibilityState).toEqual({ disabled: true });
  });

  it('reloads the list after a stale version instead of showing a confirmation', async () => {
    const page = pageTransport(
      async () => [eligible],
      async () => {
        throw new TransportError('conflict', { correlationId: 'corr-stale' });
      },
    );
    const view = await renderPage(page);

    await fireEvent.press(await readyConfirm(view));

    await waitFor(() => expect(page.get).toHaveBeenCalledTimes(2));
    expect(view.queryByTestId('confirmed-apt-1')).toBeNull();
  });

  it('allows a retry after a server failure and keeps the row unchanged', async () => {
    const page = pageTransport(
      async () => [eligible],
      async () => {
        throw new TransportError('server', { correlationId: 'corr-failure' });
      },
    );
    const view = await renderPage(page);

    await fireEvent.press(await readyConfirm(view));
    await waitFor(() =>
      expect(view.getByTestId('confirm-apt-1').props.accessibilityState).toEqual({ disabled: false }),
    );
    await fireEvent.press(view.getByTestId('confirm-apt-1'));

    expect(page.post).toHaveBeenCalledTimes(2);
    expect(view.queryByTestId('confirmed-apt-1')).toBeNull();
  });

  it('reconciles a lost response when the server reports the row is no longer confirmable', async () => {
    const page = pageTransport(
      async () => [eligible],
      async () => {
        throw new TransportError('validation', {
          correlationId: 'corr-not-confirmable',
          code: 'NOT_CONFIRMABLE',
        });
      },
    );
    const view = await renderPage(page);

    await fireEvent.press(await readyConfirm(view));

    await waitFor(() => expect(page.get).toHaveBeenCalledTimes(2));
    expect(view.queryByTestId('confirmed-apt-1')).toBeNull();
  });

  it('ends the session when confirmation is rejected as unauthenticated', async () => {
    const onSessionExpired = jest.fn();
    const page = pageTransport(
      async () => [eligible],
      async () => {
        throw new TransportError('unauthenticated', { correlationId: 'corr-expired' });
      },
    );
    const view = await renderPage(page, onSessionExpired);

    await fireEvent.press(await readyConfirm(view));

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(view.getByTestId('confirm-apt-1').props.accessibilityState).toEqual({ disabled: false }),
    );
  });
});
