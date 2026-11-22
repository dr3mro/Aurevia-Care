/**
 * PAT-01 race guard: a second command that reaches the page before React can
 * re-render the disabled control must not issue a second request. The
 * presentational screen is mocked as a boundary so its callback can be invoked
 * twice in one turn; this is a deterministic test, not a timing race.
 */
import { jest } from '@jest/globals';
import { act, render, waitFor } from '@testing-library/react-native';

import type { Transport } from '../api/transport';
import type { AppointmentView } from '../features/appointments/appointments';
import type { AppointmentListScreenProps } from '../features/appointments/AppointmentListScreen';

import { AppointmentListPage } from './patientPages';

/** Captured callback from the mocked presentational screen. */
let mockOnConfirm: AppointmentListScreenProps['onConfirm'] | undefined;

jest.mock('../features/appointments/AppointmentListScreen', () => ({
  AppointmentListScreen: (props: AppointmentListScreenProps) => {
    mockOnConfirm = props.onConfirm;
    return null;
  },
}));

/** Synthetic read row carrying the version the command must echo. */
const row: AppointmentView = {
  key: 'synthetic-organization\u0000apt-1',
  id: 'apt-1',
  version: 3,
  timeZone: 'Asia/Riyadh',
  localStart: '13:00',
  status: 'booked',
  confirmation: { kind: 'pending', confirmedAt: null },
  confirmedLocal: null,
};

/** Synthetic wire payload the installed page parses into a display row. */
const wireAppointment = {
  id: row.id,
  version: row.version,
  patientId: 'synthetic-patient',
  facilityCode: 'synthetic-facility',
  facilityTimeZone: row.timeZone,
  startsAt: '2026-11-21T10:00:00Z',
  status: 'booked',
  confirmationEnabled: true,
  confirmedAt: null,
};

/** Transport whose write never settles, holding the command in flight. */
function pageTransport(): { transport: Transport; post: jest.Mock } {
  const post = jest.fn(() => new Promise<unknown>(() => undefined));
  return {
    transport: {
      get: jest.fn(async () => [wireAppointment]) as Transport['get'],
      post: post as Transport['post'],
      put: jest.fn(async () => undefined) as Transport['put'],
    },
    post,
  };
}

describe('installed page in-flight guard', () => {
  it('drops a second confirm that arrives before the first command settles', async () => {
    const page = pageTransport();
    await render(
      <AppointmentListPage
        transport={page.transport}
        locale="en"
        organizationId="synthetic-organization"
        patientId="synthetic-patient"
        onSessionExpired={() => undefined}
      />,
    );
    await waitFor(() => expect(mockOnConfirm).toBeDefined());

    await act(async () => {
      mockOnConfirm?.(row);
      mockOnConfirm?.(row);
    });

    expect(page.post).toHaveBeenCalledTimes(1);
    expect(page.post).toHaveBeenCalledWith('/api/v1/patient/appointments/apt-1/confirm', {
      patientId: 'synthetic-patient',
      expectedVersion: 3,
    });
  });
});
