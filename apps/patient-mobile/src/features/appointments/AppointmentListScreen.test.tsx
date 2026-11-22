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

/** Confirm now receives the whole read row so the page can echo its version. */
function confirmRecorder(): {
  fn: (row: AppointmentView) => void;
  calls: string[];
  rows: AppointmentView[];
} {
  const calls: string[] = [];
  const rows: AppointmentView[] = [];
  return {
    fn: (row: AppointmentView) => {
      calls.push(row.id);
      rows.push(row);
    },
    calls,
    rows,
  };
}

const pending: AppointmentView = {
  key: 'org-a\u0000apt-1',
  id: 'apt-1',
  version: 3,
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
        onConfirm={confirmRecorder().fn}
        onOpenDetails={recorder().fn}
        onRevokeDevice={recorder().fn}
      />,
    );
    expect(view.getByTestId('generic-notice').props.children).toContain(notice.title);
    expect(view.getByTestId('empty-appointments')).toBeTruthy();
    expect(view.queryByTestId('appointment-apt-1')).toBeNull();
  });

  it('renders status and facility time zone and only confirms an eligible visit', async () => {
    const confirm = confirmRecorder();
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
    expect(confirm.rows[0]?.version).toBe(3);
    await fireEvent.press(view.getByTestId('details-apt-1'));
    expect(details.calls).toEqual(['apt-1']);
    expect(view.queryByTestId('confirmed-apt-1')).toBeNull();
  });

  it('passes the read version to the command and disables the control while it is pending', async () => {
    const confirm = confirmRecorder();
    const view = await render(
      <AppointmentListScreen
        appointments={[pending]}
        notice={notice}
        patientId="patient-1"
        devices={[]}
        onConfirm={confirm.fn}
        pendingAppointmentId="apt-1"
        onOpenDetails={recorder().fn}
        onRevokeDevice={recorder().fn}
      />,
    );
    const control = view.getByTestId('confirm-apt-1');
    expect(control.props.accessibilityState).toEqual({ disabled: true });
    await fireEvent.press(control);
    expect(confirm.calls).toEqual([]);
  });

  it('disables every confirm control while any appointment command is in flight', async () => {
    const confirm = confirmRecorder();
    const second: AppointmentView = {
      ...pending,
      key: 'org-a\u0000apt-2',
      id: 'apt-2',
    };
    const view = await render(
      <AppointmentListScreen
        appointments={[pending, second]}
        notice={notice}
        patientId="patient-1"
        devices={[]}
        onConfirm={confirm.fn}
        pendingAppointmentId="apt-1"
        onOpenDetails={recorder().fn}
        onRevokeDevice={recorder().fn}
      />,
    );
    expect(view.getByTestId('confirm-apt-1').props.accessibilityState).toEqual({ disabled: true });
    expect(view.getByTestId('confirm-apt-2').props.accessibilityState).toEqual({ disabled: true });
    await fireEvent.press(view.getByTestId('confirm-apt-2'));
    expect(confirm.calls).toEqual([]);
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
        onConfirm={confirmRecorder().fn}
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
        onConfirm={confirmRecorder().fn}
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
        onConfirm={confirmRecorder().fn}
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
        onConfirm={confirmRecorder().fn}
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

describe('AppointmentListScreen approved design (BUILD-71, PAT-01, UX-01)', () => {
  const designed: AppointmentView = {
    ...pending,
    dateLabel: 'Thu, Nov 26',
    timeLabel: '2:30 PM',
    visitLine: 'Follow-up with Dr. Noor Karim',
    facilityLine: 'Harbor Family Clinic, Brooklyn site',
  };

  it('shows the designed date, visit and facility lines with labelled controls in English', async () => {
    const view = await render(
      <AppointmentListScreen
        appointments={[designed]}
        notice={notice}
        patientId="patient-1"
        devices={[activeDevice]}
        onConfirm={confirmRecorder().fn}
        onOpenDetails={recorder().fn}
        onRevokeDevice={recorder().fn}
      />,
    );
    expect(view.getByTestId('appointment-list-title')).toHaveTextContent('Appointments');
    expect(view.getByTestId('time-apt-1')).toHaveTextContent('Thu, Nov 26 2:30 PM (Asia/Riyadh)');
    expect(view.getByLabelText('visit-apt-1')).toHaveTextContent('Follow-up with Dr. Noor Karim');
    expect(view.getByLabelText('facility-apt-1')).toHaveTextContent(/Harbor Family Clinic, Brooklyn site/);
    expect(view.getByTestId('status-pill-apt-1')).toHaveTextContent(/Please confirm/);
    expect(view.getByLabelText('Confirm appointment').props.accessibilityState).toEqual({ disabled: false });
    expect(view.getByLabelText('View details')).toBeTruthy();
    expect(view.getByLabelText('Revoke device')).toBeTruthy();
  });

  it('renders a completed visit with a neutral, worded status pill', async () => {
    const completed: AppointmentView = {
      ...pending,
      status: 'completed',
      confirmation: { kind: 'unavailable', confirmedAt: null },
    };
    const view = await render(
      <AppointmentListScreen
        appointments={[completed]}
        notice={notice}
        patientId="patient-1"
        devices={[]}
        onConfirm={confirmRecorder().fn}
        onOpenDetails={recorder().fn}
        onRevokeDevice={recorder().fn}
      />,
    );
    expect(view.getByTestId('status-pill-apt-1')).toHaveTextContent(/Completed/);
    expect(view.queryByLabelText('Confirm appointment')).toBeNull();
  });

  it('renders Arabic RTL copy, mirrored tab bar and Arabic control labels', async () => {
    const tabs = recorder();
    const view = await render(
      <AppointmentListScreen
        appointments={[designed]}
        notice={notice}
        patientId="patient-1"
        devices={[]}
        onConfirm={confirmRecorder().fn}
        onOpenDetails={recorder().fn}
        onRevokeDevice={recorder().fn}
        locale="ar"
        showTabBar
        activeTab="appointments"
        onTab={tabs.fn}
      />,
    );
    expect(view.getByTestId('appointment-list').props.style).toEqual(
      expect.arrayContaining([expect.objectContaining({ direction: 'rtl' })]),
    );
    expect(view.getByTestId('appointment-list-title')).toHaveTextContent('المواعيد');
    expect(view.getByTestId('status-pill-apt-1')).toHaveTextContent(/يرجى التأكيد/);
    expect(view.getByLabelText('تأكيد الموعد')).toBeTruthy();
    expect(view.getByLabelText('المواعيد tab')).toHaveTextContent(/المواعيد/);
    const onTab = view.getByTestId('tab-button-reports');
    await fireEvent.press(onTab);
    expect(tabs.calls).toEqual(['reports']);
  });
});
