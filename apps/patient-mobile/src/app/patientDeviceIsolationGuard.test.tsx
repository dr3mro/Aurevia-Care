/**
 * PAT-01 tenant/patient isolation at the confirmation boundary: a pressed id
 * that is not an active device of this patient must open no destructive step.
 * The presentational list is mocked only to inject such an id through its typed
 * callback and to render the detail content the real page supplies.
 */
import { jest } from '@jest/globals';
import { fireEvent, render, waitFor } from '@testing-library/react-native';

import type { Transport } from '../api/transport';
import type { AppointmentListScreenProps } from '../features/appointments/AppointmentListScreen';
import type { DeviceToken } from '../features/appointments/types';

import { AppointmentListPage } from './patientPages';

jest.mock('../features/appointments/AppointmentListScreen', () => {
  const react = jest.requireActual<typeof import('react')>('react');
  const native = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    AppointmentListScreen: (props: AppointmentListScreenProps) =>
      react.createElement(
        native.View,
        null,
        react.createElement(native.Pressable, {
          testID: 'push-known',
          onPress: () => props.onRevokeDevice('dev-1'),
        }),
        react.createElement(native.Pressable, {
          testID: 'push-unknown',
          onPress: () => props.onRevokeDevice('dev-2'),
        }),
        props.detailContent,
      ),
  };
});

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

/** Active device of the verified patient; dev-2 in the mock is foreign. */
const active: DeviceToken = {
  id: 'dev-1',
  patientId: 'synthetic-patient',
  state: 'active',
  token: 'synthetic-device-token',
};

describe('installed page device resolution guard', () => {
  it('opens confirmation only for a device that resolves to this patient and tenant', async () => {
    const transport: Transport = {
      get: jest.fn(async () => [wireAppointment]) as Transport['get'],
      post: jest.fn(async () => undefined) as Transport['post'],
      put: jest.fn(async () => undefined) as Transport['put'],
    };
    const remove = jest.fn(async () => undefined) as unknown as jest.Mock;
    const view = await render(
      <AppointmentListPage
        transport={transport}
        locale="en"
        organizationId="synthetic-organization"
        patientId="synthetic-patient"
        onSessionExpired={() => undefined}
        deviceClient={{ delete: remove as never }}
        initialDevices={[active]}
      />,
    );
    await waitFor(() => expect(view.getByTestId('push-known')).toBeTruthy());

    await fireEvent.press(view.getByTestId('push-known'));
    expect(view.getByTestId('device-revocation-dev-1')).toBeTruthy();
    await fireEvent.press(view.getByTestId('device-revoke-cancel'));

    await fireEvent.press(view.getByTestId('push-unknown'));
    expect(view.queryByTestId('device-revocation-dev-2')).toBeNull();
    expect(remove).not.toHaveBeenCalled();
  });
});
