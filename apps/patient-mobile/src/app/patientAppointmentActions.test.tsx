/**
 * PAT-01 acceptance: appointment details, explicit device revocation and safe
 * failure handling on the installed, signed-in appointments page.
 *
 * The real page is rendered with real PatientPageProps and a synthetic
 * authenticated transport; only the device write port and the host-owned device
 * seed are injected. Every payload is synthetic and no credential or clinical
 * narrative is asserted.
 */
import { jest } from '@jest/globals';
import { fireEvent, render, waitFor } from '@testing-library/react-native';

import { TransportError } from '../api/transport';
import type { Transport } from '../api/transport';
import type { DeviceWriteClient } from '../features/appointments/deviceClient';
import type { DeviceToken } from '../features/appointments/types';

import { AppointmentListPage } from './patientPages';

/** Two distinct wires so "selects the intended appointment" is observable. */
const wires = [
  {
    id: 'apt-1',
    version: 1,
    patientId: 'synthetic-patient',
    facilityCode: 'synthetic-facility',
    facilityTimeZone: 'Asia/Riyadh',
    startsAt: '2026-11-21T10:00:00Z',
    status: 'booked',
    confirmationEnabled: true,
    confirmedAt: null,
  },
  {
    id: 'apt-2',
    version: 2,
    patientId: 'synthetic-patient',
    facilityCode: 'synthetic-facility',
    facilityTimeZone: 'Asia/Riyadh',
    startsAt: '2026-11-22T14:00:00Z',
    status: 'completed',
    confirmationEnabled: false,
    confirmedAt: null,
  },
];

/** Active device owned by the verified patient in the active tenant. */
const active: DeviceToken = {
  id: 'dev-1',
  patientId: 'synthetic-patient',
  state: 'active',
  token: 'synthetic-device-token',
};
/** Active device of another patient in the same tenant. */
const foreign: DeviceToken = {
  ...active,
  id: 'dev-2',
  patientId: 'other-patient',
  token: 'synthetic-foreign-token',
};
/** Already revoked device of the verified patient. */
const revoked: DeviceToken = { ...active, id: 'dev-3', state: 'revoked' };
/** Host-supplied, tenant-scoped seed in deliberately mixed order. */
const seed: readonly DeviceToken[] = [foreign, active, revoked];

/** One render fixture with fully controllable write and session hooks. */
interface Fixture {
  readonly transport: Transport;
  readonly client: DeviceWriteClient;
  readonly remove: jest.Mock;
  readonly onSessionExpired: jest.Mock;
}

/** Build a fixture around one synthetic DELETE outcome. */
function fixture(remove: () => Promise<unknown>): Fixture {
  const removeMock = jest.fn(remove) as unknown as jest.Mock;
  return {
    transport: {
      get: jest.fn(async () => wires) as Transport['get'],
      post: jest.fn(async () => undefined) as Transport['post'],
      put: jest.fn(async () => undefined) as Transport['put'],
    },
    client: { delete: removeMock as unknown as DeviceWriteClient['delete'] },
    remove: removeMock,
    onSessionExpired: jest.fn(),
  };
}

/** Render the installed page with the real props and injected device seams. */
function renderPage(fx: Fixture) {
  return render(
    <AppointmentListPage
      transport={fx.transport}
      locale="en"
      organizationId="synthetic-organization"
      patientId="synthetic-patient"
      onSessionExpired={fx.onSessionExpired}
      deviceClient={fx.client}
      initialDevices={seed}
    />,
  );
}

/** Open the explicit confirmation step for the active device. */
async function openConfirm(view: Awaited<ReturnType<typeof renderPage>>): Promise<void> {
  await waitFor(() => expect(view.getByTestId('revoke-dev-1')).toBeTruthy());
  await fireEvent.press(view.getByTestId('revoke-dev-1'));
  expect(view.getByTestId('device-revocation-dev-1')).toBeTruthy();
  expect(view.queryByTestId('device-revocation-error')).toBeNull();
}

/** Assert the single patient-scoped DELETE this story is allowed to issue. */
function expectOneDelete(fx: Fixture): void {
  expect(fx.remove).toHaveBeenCalledTimes(1);
  expect(fx.remove).toHaveBeenCalledWith(
    '/api/v1/patient/appointments/devices/dev-1?patientId=synthetic-patient',
  );
}

describe('installed signed-in appointments page', () => {
  it('opens the exact row whose details control was pressed and closes with Back', async () => {
    const view = await renderPage(fixture(async () => undefined));
    await waitFor(() => expect(view.getByTestId('details-apt-2')).toBeTruthy());

    await fireEvent.press(view.getByTestId('details-apt-2'));

    expect(view.getByTestId('appointment-detail')).toBeTruthy();
    expect(view.getByTestId('detail-status')).toHaveTextContent('completed');
    expect(view.queryByTestId('appointment-detail')).toBeTruthy();

    await fireEvent.press(view.getByTestId('appointment-detail-back'));
    expect(view.queryByTestId('appointment-detail')).toBeNull();
  });

  it('offers only active devices of the verified patient in the active tenant', async () => {
    const view = await renderPage(fixture(async () => undefined));
    await waitFor(() => expect(view.getByTestId('revoke-dev-1')).toBeTruthy());

    expect(view.queryByTestId('revoke-dev-2')).toBeNull();
    expect(view.queryByTestId('revoke-dev-3')).toBeNull();
  });

  it('sends no request when the patient cancels the confirmation', async () => {
    const fx = fixture(async () => undefined);
    const view = await renderPage(fx);
    await openConfirm(view);

    await fireEvent.press(view.getByTestId('device-revoke-cancel'));

    expect(view.queryByTestId('device-revocation-dev-1')).toBeNull();
    expect(view.getByTestId('revoke-dev-1')).toBeTruthy();
    expect(fx.remove).not.toHaveBeenCalled();
  });

  it('sends one DELETE and refreshes devices when the patient confirms', async () => {
    const fx = fixture(async () => undefined);
    const view = await renderPage(fx);
    await openConfirm(view);

    await fireEvent.press(view.getByTestId('device-revoke-confirm'));

    await waitFor(() => expectOneDelete(fx));
    await waitFor(() => expect(view.queryByTestId('revoke-dev-1')).toBeNull());
    expect(view.queryByTestId('device-revocation-dev-1')).toBeNull();
    expect(view.queryByTestId('device-revocation-error')).toBeNull();
  });

  it('ends the session once on 401 and keeps the device', async () => {
    const fx = fixture(async () => {
      throw new TransportError('unauthenticated', { correlationId: 'corr-expired' });
    });
    const view = await renderPage(fx);
    await openConfirm(view);

    await fireEvent.press(view.getByTestId('device-revoke-confirm'));

    await waitFor(() => expect(fx.onSessionExpired).toHaveBeenCalledTimes(1));
    expect(view.queryByTestId('device-revocation-dev-1')).toBeNull();
    expect(view.getByTestId('revoke-dev-1')).toBeTruthy();
  });

  it('refreshes silently for an already-revoked device without an error view', async () => {
    const fx = fixture(async () => {
      throw new TransportError('server', { correlationId: 'corr-gone', code: 'NOT_FOUND' });
    });
    const view = await renderPage(fx);
    await openConfirm(view);

    await fireEvent.press(view.getByTestId('device-revoke-confirm'));

    await waitFor(() => expectOneDelete(fx));
    await waitFor(() => expect(view.queryByTestId('revoke-dev-1')).toBeNull());
    expect(view.queryByTestId('device-revocation-error')).toBeNull();
    expect(fx.onSessionExpired).not.toHaveBeenCalled();
  });

  it('keeps the device and shows one generic state on a transport failure', async () => {
    const fx = fixture(async () => {
      throw new TransportError('network', { correlationId: 'corr-offline' });
    });
    const view = await renderPage(fx);
    await openConfirm(view);

    await fireEvent.press(view.getByTestId('device-revoke-confirm'));

    await waitFor(() => expect(view.getByTestId('device-revocation-error')).toBeTruthy());
    expectOneDelete(fx);
    expect(view.getByTestId('revoke-dev-1')).toBeTruthy();
    expect(view.queryByText(/synthetic-device-token/)).toBeNull();
    expect(fx.onSessionExpired).not.toHaveBeenCalled();
  });
});
