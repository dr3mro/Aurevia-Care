/**
 * Presentation branches for the selected appointment detail and the explicit
 * device-revocation step (PAT-01, UX-01). Both locales and both states of each
 * conditional line are rendered and asserted with synthetic data only.
 */
import { jest } from '@jest/globals';
import { fireEvent, render } from '@testing-library/react-native';

import { AppointmentDetail } from './AppointmentDetail';
import { DeviceRevocationConfirm } from './DeviceRevocationConfirm';
import type { AppointmentView } from './appointments';
import { screenText } from './patientDesign';
import type { Locale } from './patientDesign';

/** Base display row; the confirmed variant adds a recorded instant. */
const row: AppointmentView = {
  key: 'synthetic-organization\u0000apt-1',
  id: 'apt-1',
  version: 1,
  timeZone: 'Asia/Riyadh',
  localStart: '13:00',
  status: 'booked',
  confirmation: { kind: 'pending', confirmedAt: null },
  confirmedLocal: null,
  facilityLine: 'Synthetic clinic',
};

describe('AppointmentDetail presentation', () => {
  it.each<Locale>(['en', 'ar'])('shows the selected row and closes with Back in %s', async (locale) => {
    const onBack = jest.fn();
    const view = await render(
      <AppointmentDetail
        appointment={row}
        locale={locale}
        backLabel={screenText(locale, 'back')}
        onBack={onBack}
      />,
    );

    expect(view.getByTestId('detail-time')).toHaveTextContent(new RegExp(row.localStart));
    expect(view.getByTestId('detail-facility')).toHaveTextContent('Synthetic clinic');
    expect(view.getByTestId('detail-status')).toHaveTextContent('booked');
    expect(view.queryByTestId('detail-confirmed')).toBeNull();

    await fireEvent.press(view.getByTestId('appointment-detail-back'));
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('prefers the civil date and clock time supplied by the loader', async () => {
    const view = await render(
      <AppointmentDetail
        appointment={{ ...row, dateLabel: 'Thu, Nov 26', timeLabel: '2:30 PM' }}
        locale="en"
        backLabel="Back"
        onBack={() => undefined}
      />,
    );
    expect(view.getByTestId('detail-time')).toHaveTextContent(/Thu, Nov 26/);
    expect(view.getByTestId('detail-time')).toHaveTextContent(/2:30 PM/);
    expect(view.getByTestId('detail-time')).not.toHaveTextContent(/13:00/);
  });

  it('shows the recorded confirmation time when the row is confirmed', async () => {
    const view = await render(
      <AppointmentDetail
        appointment={{ ...row, confirmedLocal: '14:05' }}
        locale="en"
        backLabel="Back"
        onBack={() => undefined}
      />,
    );
    expect(view.getByTestId('detail-confirmed')).toHaveTextContent(/14:05/);
  });

  it('falls back to the status when no facility line was supplied', async () => {
    // The property must be absent rather than present with `undefined`: the
    // display row declares an optional string, so omitting the key is the only
    // way to exercise the fallback under strict optional-property typing.
    const { facilityLine: _omitted, ...withoutFacility } = row;
    const view = await render(
      <AppointmentDetail
        appointment={withoutFacility}
        locale="en"
        backLabel="Back"
        onBack={() => undefined}
      />,
    );
    expect(view.getByTestId('detail-facility')).toHaveTextContent('booked');
  });
});

describe('DeviceRevocationConfirm presentation', () => {
  it.each<Locale>(['en', 'ar'])('holds the decision until Confirm in %s', async (locale) => {
    const onCancel = jest.fn();
    const onConfirm = jest.fn();
    const view = await render(
      <DeviceRevocationConfirm
        deviceId="dev-1"
        locale={locale}
        title={screenText(locale, 'revokeConfirmTitle')}
        body={screenText(locale, 'revokeConfirmBody')}
        cancelLabel={screenText(locale, 'revokeCancel')}
        confirmLabel={screenText(locale, 'revokeConfirm')}
        errorMessage={null}
        pending={false}
        onCancel={onCancel}
        onConfirm={onConfirm}
      />,
    );

    expect(view.getByTestId('device-revocation-dev-1')).toBeTruthy();
    expect(view.queryByTestId('device-revocation-error')).toBeNull();
    await fireEvent.press(view.getByTestId('device-revoke-cancel'));
    await fireEvent.press(view.getByTestId('device-revoke-confirm'));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('shows a generic error line and disables Confirm while in flight', async () => {
    const view = await render(
      <DeviceRevocationConfirm
        deviceId="dev-1"
        locale="en"
        title={screenText('en', 'revokeConfirmTitle')}
        body={screenText('en', 'revokeConfirmBody')}
        cancelLabel={screenText('en', 'revokeCancel')}
        confirmLabel={screenText('en', 'revokeConfirm')}
        errorMessage={screenText('en', 'revokeError')}
        pending
        onCancel={() => undefined}
        onConfirm={() => undefined}
      />,
    );

    expect(view.getByTestId('device-revocation-error')).toHaveTextContent(
      screenText('en', 'revokeError'),
    );
    expect(view.getByTestId('device-revoke-confirm').props.accessibilityState.disabled).toBe(true);
  });
});
