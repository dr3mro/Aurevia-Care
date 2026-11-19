import { fireEvent, render } from '@testing-library/react-native';

import { PatientFlowHome } from './PatientFlowHome';
import type { FlowPresentation } from './types';

function presentation(overrides: Partial<FlowPresentation> = {}): FlowPresentation {
  return {
    organizationId: '11111111-1111-1111-1111-111111111111',
    locale: 'en',
    platform: 'android',
    rtl: false,
    direction: 'ltr',
    settingsLabel: 'Open Android system settings',
    secureStorageLabel: 'Protected by Android Keystore',
    policyVersion: 3,
    issuedAt: '2026-11-19T12:00:00Z',
    timeZoneContext: 'Europe/London',
    accessibilityProfile: 'WCAG-2.2-AA',
    noticeKinds: ['new_message', 'reauth_required'],
    ...overrides,
  };
}

describe('S-G3-11 accessible patient flow home', () => {
  const flows = ['appointments', 'reports', 'messages', 'enrollment'] as const;

  it('renders every flow with an accessible label and fires the selection', async () => {
    const selected: string[] = [];
    const view = await render(
      <PatientFlowHome
        presentation={presentation()}
        flows={flows}
        previewNotice="new_message"
        onSelectFlow={(flow) => selected.push(flow)}
        onOpenSettings={() => undefined}
      />,
    );
    for (const label of ['Appointments', 'Reports', 'Messages', 'Support and identity']) {
      expect(view.getByLabelText(label)).toBeTruthy();
    }
    await fireEvent.press(view.getByLabelText('Messages'));
    expect(selected).toEqual(['messages']);
  });

  it('mirrors the flow row for Arabic RTL and keeps labels localized', async () => {
    const view = await render(
      <PatientFlowHome
        presentation={presentation({
          locale: 'ar', rtl: true, direction: 'rtl',
          settingsLabel: 'افتح إعدادات Android',
          secureStorageLabel: 'محمي بواسطة Android Keystore',
          noticeKinds: ['new_instruction'],
        })}
        flows={['messages']}
        previewNotice="new_instruction"
        onSelectFlow={() => undefined}
        onOpenSettings={() => undefined}
      />,
    );
    expect(view.getByTestId('patient-flow-home').props.style.flexDirection).toBe('row-reverse');
    expect(view.getByLabelText('الرسائل')).toBeTruthy();
    expect(view.getByTestId('notice-preview').children.join('')).toContain('لديك تعليمات جديدة.');
  });

  it('honours the server-authored direction even when locale disagrees', async () => {
    const view = await render(
      <PatientFlowHome
        presentation={presentation({ locale: 'en', rtl: true, direction: 'rtl' })}
        flows={[]}
        previewNotice="appointment_status"
        onSelectFlow={() => undefined}
        onOpenSettings={() => undefined}
      />,
    );
    expect(view.getByTestId('patient-flow-home').props.style.flexDirection).toBe('row-reverse');
  });

  it('does not preview a notice kind the tenant has not approved', async () => {
    const view = await render(
      <PatientFlowHome
        presentation={presentation({ noticeKinds: ['reauth_required'] })}
        flows={[]}
        previewNotice="new_message"
        onSelectFlow={() => undefined}
        onOpenSettings={() => undefined}
      />,
    );
    expect(view.getByTestId('notice-preview').children.join('')).toBe('');
  });

  it('keeps the row forward for LTR locales', async () => {
    const view = await render(
      <PatientFlowHome
        presentation={presentation()}
        flows={[]}
        previewNotice="appointment_status"
        onSelectFlow={() => undefined}
        onOpenSettings={() => undefined}
      />,
    );
    expect(view.getByTestId('patient-flow-home').props.style.flexDirection).toBe('row');
    expect(view.getByText('No flows available')).toBeTruthy();
  });

  it('exposes platform secure-storage wording, time zone and an accessible settings action', async () => {
    let opened = false;
    const view = await render(
      <PatientFlowHome
        presentation={presentation({ platform: 'ios', secureStorageLabel: 'Protected by iOS Keychain', settingsLabel: 'Open iOS Settings' })}
        flows={[]}
        previewNotice="reauth_required"
        onSelectFlow={() => undefined}
        onOpenSettings={() => { opened = true; }}
      />,
    );
    expect(view.getByTestId('secure-storage').children.join('')).toContain('Protected by iOS Keychain');
    expect(view.getByTestId('time-zone').children.join('')).toContain('Europe/London');
    expect(view.getByTestId('accessibility-profile').children.join('')).toContain('WCAG-2.2-AA');
    await fireEvent.press(view.getByLabelText('Open iOS Settings'));
    expect(opened).toBe(true);
  });
});
