/**
 * PAT-01 race guard: a second revocation Confirm that reaches the page before
 * React can re-render the disabled control must not issue a second DELETE. The
 * presentational confirmation step is mocked as a boundary so its callback can
 * be invoked twice in one turn; this is deterministic, not a timing race.
 */
import { jest } from '@jest/globals';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import type { Transport } from '../api/transport';
import type { DeviceRevocationConfirmProps } from '../features/appointments/DeviceRevocationConfirm';
import type { DeviceWriteClient } from '../features/appointments/deviceClient';
import type { DeviceToken } from '../features/appointments/types';

import { AppointmentListPage } from './patientPages';

/** Captured callback from the mocked confirmation step. */
let mockOnConfirm: (() => void) | undefined;

jest.mock('../features/appointments/DeviceRevocationConfirm', () => ({
  DeviceRevocationConfirm: (props: DeviceRevocationConfirmProps) => {
    mockOnConfirm = props.onConfirm;
    return null;
  },
}));

/** Synthetic wire appointment so the page reaches its loaded state. */
const wireAppointment = {
  id: 'apt-1',
  version: 1,
  patientId: 'synthetic-patient',
  facilityCode: 'synthetic-facility',
  facilityTimeZone: 'Asia/Riyadh',
  startsAt: '2026-11-21T10:00:00Z',
  status: 'booked',
  confirmationEnabled: true,
  confirmedAt: null,
};

/** One active device of the verified patient. */
const active: DeviceToken = {
  id: 'dev-1',
  patientId: 'synthetic-patient',
  state: 'active',
  token: 'synthetic-device-token',
};

describe('installed page revocation in-flight guard', () => {
  it('drops a second Confirm that arrives before the first DELETE settles', async () => {
    // The write never settles, so the single-flight slot stays claimed.
    const remove = jest.fn(() => new Promise<void>(() => undefined)) as unknown as jest.Mock;
    const transport: Transport = {
      get: jest.fn(async () => [wireAppointment]) as Transport['get'],
      post: jest.fn(async () => undefined) as Transport['post'],
      put: jest.fn(async () => undefined) as Transport['put'],
    };
    const client: DeviceWriteClient = {
      delete: remove as unknown as DeviceWriteClient['delete'],
    };

    const view = await render(
      <AppointmentListPage
        transport={transport}
        locale="en"
        organizationId="synthetic-organization"
        patientId="synthetic-patient"
        onSessionExpired={() => undefined}
        deviceClient={client}
        initialDevices={[active]}
      />,
    );
    await waitFor(() => expect(view.getByTestId('revoke-dev-1')).toBeTruthy());
    await fireEvent.press(view.getByTestId('revoke-dev-1'));
    await waitFor(() => expect(mockOnConfirm).toBeDefined());

    await act(async () => {
      mockOnConfirm?.();
      mockOnConfirm?.();
    });

    expect(remove).toHaveBeenCalledTimes(1);
    expect(remove).toHaveBeenCalledWith(
      '/api/v1/patient/appointments/devices/dev-1?patientId=synthetic-patient',
    );
  });
});
