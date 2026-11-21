import { fireEvent, render } from '@testing-library/react-native';

import type { AppointmentView } from './appointments';
import type { DeviceToken, GenericNotice } from './types';
import { AppointmentListScreen } from './AppointmentListScreen';

/** S-G3-01: patient list, confirmation, generic banner and device revocation (PAT-01). */
const notice: GenericNotice = { title: 'Appointment update', body: 'Open the app to view details.' };

function recorder(): { fn: (id: string) => void; calls: string[] } {
  const calls: string[] = [];
  return {
    fn: (id: string) => {
      calls.push(id);
    },
    calls,
  };
}

const pending: AppointmentView = {
  key: 'org-a\u0000apt-1',
  id: 'apt-1',
  timeZone: 'Asia/Riyadh',
  localStart: '10:00',
  status: 'booked',
  confirmation: { kind: 'pending', confirmedAt: null },
  confirmedLocal: null,
};

const activeDevice: DeviceToken = {
  id: 'dev-1',
  patientId: 'patient-1',
  state: 'active',
  token: 'synthetic-token',
};

describe('AppointmentListScreen', () => {
  it('shows the generic notice and an empty state without appointments', async () => {
    const view = await render(
      <AppointmentListScreen
        appointments={[]}
        notice={notice}
        patientId="patient-1"
        devices={[]}
        onConfirm={recorder().fn}
        onOpenDetails={recorder().fn}
        onRevokeDevice={recorder().fn}
      />,
    );
    expect(view.getByTestId('generic-notice').props.children).toContain(notice.title);
    expect(view.getByTestId('empty-appointments')).toBeTruthy();
    expect(view.queryByTestId('appointment-apt-1')).toBeNull();
  });

  it('renders status and facility time zone and only confirms an eligible visit', async () => {
    const confirm = recorder();
    const details = recorder();
    const view = await render(
      <AppointmentListScreen
        appointments={[pending]}
        notice={notice}
        patientId="patient-1"
        devices={[]}
        onConfirm={confirm.fn}
        onOpenDetails={details.fn}
        onRevokeDevice={recorder().fn}
      />,
    );
    expect(view.getByTestId('status-apt-1').props.children).toBe('booked');
    expect(view.getByTestId('time-apt-1').props.children).toContain('Asia/Riyadh');
    await fireEvent.press(view.getByTestId('confirm-apt-1'));
    expect(confirm.calls).toEqual(['apt-1']);
    await fireEvent.press(view.getByTestId('details-apt-1'));
    expect(details.calls).toEqual(['apt-1']);
    expect(view.queryByTestId('confirmed-apt-1')).toBeNull();
  });

  it('shows a confirmed visit without a confirmation action', async () => {
    const confirmed: AppointmentView = {
      ...pending,
      confirmation: { kind: 'confirmed', confirmedAt: '2026-11-19T10:00:00Z' },
      confirmedLocal: '12:00',
    };
    const view = await render(
      <AppointmentListScreen
        appointments={[confirmed]}
        notice={notice}
        patientId="patient-1"
        devices={[]}
        onConfirm={recorder().fn}
        onOpenDetails={recorder().fn}
        onRevokeDevice={recorder().fn}
      />,
    );
    expect(view.getByTestId('confirmed-apt-1').props.children).toContain('12:00');
    expect(view.queryByTestId('confirm-apt-1')).toBeNull();
  });

  it('offers no confirmation action for an ineligible visit', async () => {
    const unavailable: AppointmentView = {
      ...pending,
      confirmation: { kind: 'unavailable', confirmedAt: null },
      confirmedLocal: null,
    };
    const view = await render(
      <AppointmentListScreen
        appointments={[unavailable]}
        notice={notice}
        patientId="patient-1"
        devices={[]}
        onConfirm={recorder().fn}
        onOpenDetails={recorder().fn}
        onRevokeDevice={recorder().fn}
      />,
    );
    expect(view.queryByTestId('confirm-apt-1')).toBeNull();
    expect(view.queryByTestId('confirmed-apt-1')).toBeNull();
  });

  it('offers revocation only for active devices', async () => {
    const revoke = recorder();
    const view = await render(
      <AppointmentListScreen
        appointments={[]}
        notice={notice}
        patientId="patient-1"
        devices={[activeDevice, { ...activeDevice, id: 'dev-2', state: 'revoked' }]}
        onConfirm={recorder().fn}
        onOpenDetails={recorder().fn}
        onRevokeDevice={revoke.fn}
      />,
    );
    await fireEvent.press(view.getByTestId('revoke-dev-1'));
    expect(revoke.calls).toEqual(['dev-1']);
    expect(view.queryByTestId('revoke-dev-2')).toBeNull();
  });

  it('never offers to revoke a device owned by another patient', async () => {
    const revoke = recorder();
    const foreign = { ...activeDevice, id: 'dev-2', patientId: 'patient-2' };
    const view = await render(
      <AppointmentListScreen
        appointments={[]}
        notice={notice}
        patientId="patient-1"
        devices={[activeDevice, foreign]}
        onConfirm={recorder().fn}
        onOpenDetails={recorder().fn}
        onRevokeDevice={revoke.fn}
      />,
    );
    expect(view.getByTestId('revoke-dev-1')).toBeTruthy();
    expect(view.queryByTestId('revoke-dev-2')).toBeNull();
    await fireEvent.press(view.getByTestId('revoke-dev-1'));
    expect(revoke.calls).toEqual(['dev-1']);
  });
});
