/**
 * READY-33 acceptance tests for the minimum Expo entry (PAT-01, UX-01) plus the
 * READY-34 four-tab navigation contract. The placeholder title remains part of
 * the shell so the registered root component is still observable in both
 * languages, while the navigator adds the four contract tabs.
 */
import { jest } from '@jest/globals';
import { fireEvent, render, waitFor, within } from '@testing-library/react-native';
import { registerRootComponent } from 'expo';

import App from '../../App';
import '../../index';
import { clearToken } from '../api/tokenStore';
import { TransportError } from '../api/transport';
import type { Transport } from '../api/transport';
import type { DeviceWriteClient } from '../features/appointments/deviceClient';
import type { DeviceToken } from '../features/appointments/types';
import { appText } from './messages';
import { RootNavigator } from './RootNavigator';
import { TAB_DEFINITIONS } from './tabs';

jest.mock('expo', () => ({
  registerRootComponent: jest.fn(),
}));

// The signed-in reports tab binds the OS credential store. Headless tests have
// no native keychain, so supply the same narrow adapter the real module exposes.
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(async () => null),
  setItemAsync: jest.fn(async () => undefined),
  deleteItemAsync: jest.fn(async () => undefined),
}));

// The shell creates a transport on mount; headless tests supply synthetic
// empty projections and observe token clearing without touching OS storage.
jest.mock('../api/tokenStore', () => ({
  clearToken: jest.fn(async () => undefined),
  loadToken: jest.fn(async () => null),
  saveToken: jest.fn(async () => undefined),
}));

jest.mock('./providers', () => {
  const actual = jest.requireActual<Record<string, unknown>>('./providers');
  return {
    ...actual,
    createPatientTransport: jest.fn(() => ({
      get: jest.fn(async (path: string) => {
        if (path.includes('/conversations/inbox')) {
          return {
            entries: [{
              conversation: {
                id: 'conv-1',
                version: 1,
                patientId: 'synthetic-patient',
                patientSubject: 'synthetic-subject',
                unitKey: 'synthetic-lab',
                connectionId: 'conn-1',
                topic: 'follow up',
                state: 'awaiting_staff',
                assigneeSubject: null,
                patientReadSequence: 0,
                staffReadSequence: 1,
                createdAt: '2026-11-21T10:00:00Z',
              },
              unreadCount: 1,
            }],
          };
        }
        if (path.includes('/patient/reports')) {
          return [{
            reportId: 'report-1',
            revision: 1,
            issuingOrganization: 'Synthetic clinic',
            effectiveDate: '2026-11-20',
            language: 'en',
            correctionReason: null,
            supersedesRevision: null,
          }];
        }
        return [{
          id: 'apt-1',
          version: 1,
          patientId: 'synthetic-patient',
          facilityCode: 'synthetic-facility',
          facilityTimeZone: 'Asia/Riyadh',
          startsAt: '2026-11-21T10:00:00Z',
          status: 'booked',
          confirmationEnabled: true,
          confirmedAt: null,
        }];
      }),
      post: jest.fn(async () => undefined),
      put: jest.fn(async () => undefined),
    })),
  };
});

// The native direction flag is exercised through its own unit test; the
// navigator test only needs the shell, not a platform restart side effect.
jest.mock('./direction', () => ({
  applyDirection: jest.fn(),
}));

// The shared setup mock exposes only a default export, while bottom-tabs needs
// the named safe-area contexts. Provide them headlessly for this shell test.
jest.mock('react-native-safe-area-context', () => {
  const actual = jest.requireActual<Record<string, unknown>>('react-native-safe-area-context');
  return {
    ...actual,
    initialWindowMetrics: {
      frame: { x: 0, y: 0, width: 320, height: 640 },
      insets: { top: 0, left: 0, right: 0, bottom: 0 },
    },
    useSafeAreaInsets: () => ({ top: 0, left: 0, right: 0, bottom: 0 }),
    SafeAreaProvider: ({ children }: { children?: unknown }) => children,
  };
});

describe('RootNavigator placeholder', () => {
  it('renders the English application title', async () => {
    const view = await render(<RootNavigator locale="en" />);
    expect(view.getByTestId('app-title').props.children).toBe(appText('en', 'appTitle'));
    expect(appText('en', 'appTitle')).toBe('Aurevia Care');
  });

  it('renders the Arabic application title', async () => {
    const view = await render(<RootNavigator locale="ar" />);
    expect(view.getByTestId('app-title').props.children).toBe(appText('ar', 'appTitle'));
    expect(appText('ar', 'appTitle')).not.toBe(appText('en', 'appTitle'));
  });

  it('presents the title as an accessible header with the active language', async () => {
    const view = await render(<RootNavigator locale="ar" />);
    const title = view.getByTestId('app-title');
    expect(title.props.accessibilityRole).toBe('header');
    expect(title.props.accessibilityLanguage).toBe('ar');
  });
});

describe('Expo entry point', () => {
  it('App renders the placeholder root navigator', async () => {
    const view = await render(<App />);
    expect(view.getByTestId('app-title').props.children).toBe(appText('en', 'appTitle'));
  });

  it('index registers App as the root component', () => {
    expect(registerRootComponent).toHaveBeenCalledWith(App);
  });
});

describe('four-tab navigation', () => {
  it('renders exactly four tabs in the contract order', async () => {
    const view = await render(<RootNavigator locale="en" />);

    expect(TAB_DEFINITIONS.map(tab => tab.id)).toEqual([
      'appointments',
      'reports',
      'messages',
      'account',
    ]);
    for (const tab of TAB_DEFINITIONS) {
      expect(view.getByTestId(`tab-button-${tab.id}`)).toBeTruthy();
    }
  });

  it('renders the initial Appointments screen', async () => {
    const view = await render(<RootNavigator locale="en" />);

    await waitFor(() => expect(view.getByTestId('appointment-list')).toBeTruthy());
  });

  it('opens the selected appointment detail from the installed signed-in tab', async () => {
    const view = await render(<RootNavigator locale="en" />);
    await waitFor(() => expect(view.getByTestId('details-apt-1')).toBeTruthy());

    await fireEvent.press(view.getByTestId('details-apt-1'));

    expect(view.getByTestId('appointment-detail')).toBeTruthy();
    expect(view.getByTestId('detail-status')).toHaveTextContent('booked');
    await fireEvent.press(view.getByTestId('appointment-detail-back'));
    expect(view.queryByTestId('appointment-detail')).toBeNull();
  });

  it('renders each tab screen when that tab is pressed', async () => {
    const view = await render(<RootNavigator locale="en" />);

    fireEvent.press(view.getByTestId('tab-button-reports'));
    await waitFor(() => expect(view.getByLabelText('released-reports')).toBeTruthy());

    fireEvent.press(view.getByTestId('tab-button-messages'));
    await waitFor(() => expect(view.getByTestId('stack-messages')).toBeTruthy());

    fireEvent.press(view.getByTestId('tab-button-account'));
    await waitFor(() => expect(view.getByTestId('stack-account')).toBeTruthy());

    fireEvent.press(view.getByTestId('tab-button-appointments'));
    await waitFor(() => expect(view.getByTestId('appointment-list')).toBeTruthy());
  });

  it.each(['en', 'ar'] as const)('shows localized tab labels in %s', async (locale) => {
    const view = await render(<RootNavigator locale={locale} />);

    for (const tab of TAB_DEFINITIONS) {
      const button = view.getByTestId(`tab-button-${tab.id}`);
      expect(within(button).getByText(appText(locale, tab.labelKey))).toBeTruthy();
    }
  });

  it.each(['en', 'ar'] as const)('gives every tab an accessible label in %s', async (locale) => {
    const view = await render(<RootNavigator locale={locale} />);

    for (const tab of TAB_DEFINITIONS) {
      expect(view.getByTestId(`tab-button-${tab.id}`).props.accessibilityLabel).toBe(
        appText(locale, tab.accessibilityLabelKey),
      );
    }
  });

  it('pushes every account screen from the Account menu', async () => {
    const view = await render(<RootNavigator locale="en" />);

    fireEvent.press(view.getByTestId('tab-button-account'));
    await waitFor(() => expect(view.getByTestId('stack-account')).toBeTruthy());
    for (const key of ['enrollment', 'recovery', 'support', 'lockout'] as const) {
      fireEvent.press(view.getByTestId(`stack-account-open-${key}`));
      await waitFor(() => expect(view.getByTestId(`account-screen-${key}`)).toBeTruthy());
      fireEvent.press(view.getByTestId('stack-account-back'));
      await waitFor(() => expect(view.getByTestId('account-menu')).toBeTruthy());
    }
  });

  it('pushes attachments and instruction acknowledgment from Messages', async () => {
    const view = await render(<RootNavigator locale="en" />);

    fireEvent.press(view.getByTestId('tab-button-messages'));
    await waitFor(() => expect(view.getByLabelText('conversation inbox')).toBeTruthy());

    fireEvent.press(view.getByTestId('stack-messages-open-attachments'));
    await waitFor(() => expect(view.getByTestId('messages-screen-attachments')).toBeTruthy());
    fireEvent.press(view.getByTestId('stack-messages-back'));
    await waitFor(() => expect(view.getByLabelText('conversation inbox')).toBeTruthy());

    fireEvent.press(view.getByTestId('stack-messages-open-instructions'));
    await waitFor(() => expect(view.getByTestId('messages-screen-instructions')).toBeTruthy());
  });
});

describe('session expiry', () => {
  it('clears the stored token and navigates to the signed-out screen on 401', async () => {
    jest.mocked(clearToken).mockClear();
    const expiredTransport: Transport = {
      get: jest.fn(async () => {
        throw new TransportError('unauthenticated', {});
      }) as Transport['get'],
      post: jest.fn(async () => undefined) as Transport['post'],
      put: jest.fn(async () => undefined) as Transport['put'],
    };
    const view = await render(<RootNavigator locale="en" transport={expiredTransport} />);

    await waitFor(() => expect(view.getByTestId('signed-out-screen')).toBeTruthy());
    expect(clearToken).toHaveBeenCalledTimes(1);
    expect(view.getByTestId('signed-out-title')).toHaveTextContent(appText('en', 'signedOutTitle'));
  });

  it('still signs out when clearing the stored credential fails', async () => {
    jest.mocked(clearToken).mockRejectedValueOnce(new Error('keychain unavailable'));
    const expiredTransport: Transport = {
      get: jest.fn(async () => {
        throw new TransportError('unauthenticated', {});
      }) as Transport['get'],
      post: jest.fn(async () => undefined) as Transport['post'],
      put: jest.fn(async () => undefined) as Transport['put'],
    };
    const view = await render(<RootNavigator locale="en" transport={expiredTransport} />);

    await waitFor(() => expect(view.getByTestId('signed-out-screen')).toBeTruthy());
  });

  it('forwards the optional session-expiry hook', async () => {
    jest.mocked(clearToken).mockClear();
    const onSessionExpired = jest.fn();
    const expiredTransport: Transport = {
      get: jest.fn(async () => {
        throw new TransportError('unauthenticated', {});
      }) as Transport['get'],
      post: jest.fn(async () => undefined) as Transport['post'],
      put: jest.fn(async () => undefined) as Transport['put'],
    };
    await render(
      <RootNavigator locale="en" transport={expiredTransport} onSessionExpired={onSessionExpired} />,
    );
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
  });

  it('returns to the tabs when the patient chooses to sign in again', async () => {
    let calls = 0;
    const recoveringTransport: Transport = {
      get: jest.fn(async () => {
        calls += 1;
        if (calls === 1) {
          throw new TransportError('unauthenticated', {});
        }
        return [{
          id: 'apt-1',
          version: 1,
          patientId: 'synthetic-patient',
          facilityCode: 'synthetic-facility',
          facilityTimeZone: 'Asia/Riyadh',
          startsAt: '2026-11-21T10:00:00Z',
          status: 'booked',
          confirmationEnabled: true,
          confirmedAt: null,
        }];
      }) as Transport['get'],
      post: jest.fn(async () => undefined) as Transport['post'],
      put: jest.fn(async () => undefined) as Transport['put'],
    };
    const view = await render(<RootNavigator locale="en" transport={recoveringTransport} />);
    await waitFor(() => expect(view.getByTestId('signed-out-screen')).toBeTruthy());

    await fireEvent.press(view.getByTestId('sign-in-action'));
    await waitFor(() => expect(view.getByTestId('tab-button-appointments')).toBeTruthy());
  });
});

/**
 * PAT-01 acceptance for the installed shell: the signed-in session's registered
 * device must reach the appointments tab as a real revoke control. All fixtures
 * are synthetic; the write port is injected so no request leaves the harness.
 */
describe('installed device revocation', () => {
  /** Device registered for the synthetic signed-in session fixture. */
  const signedInDevice: DeviceToken = {
    id: 'dev-1',
    patientId: 'synthetic-patient',
    state: 'active',
    token: 'synthetic-device-token',
  };

  /** Build a device write port that records scoped DELETEs without a network call. */
  function writePort() {
    const remove = jest.fn(async () => undefined) as unknown as jest.Mock;
    return {
      remove,
      client: { delete: remove as unknown as DeviceWriteClient['delete'] } satisfies DeviceWriteClient,
    };
  }

  it('offers the signed-in device for revocation on the installed appointments tab', async () => {
    const view = await render(<RootNavigator locale="en" />);
    await waitFor(() => expect(view.getByTestId('revoke-dev-1')).toBeTruthy());
  });

  it('does not offer a device registered to another patient', async () => {
    const foreign: DeviceToken = { ...signedInDevice, patientId: 'other-patient' };
    const view = await render(<RootNavigator locale="en" registeredDevice={foreign} />);
    await waitFor(() => expect(view.getByTestId('appointment-list')).toBeTruthy());
    expect(view.queryByTestId('revoke-dev-1')).toBeNull();
  });

  it('does not offer a revoked device', async () => {
    const revoked: DeviceToken = { ...signedInDevice, state: 'revoked' };
    const view = await render(<RootNavigator locale="en" registeredDevice={revoked} />);
    await waitFor(() => expect(view.getByTestId('appointment-list')).toBeTruthy());
    expect(view.queryByTestId('revoke-dev-1')).toBeNull();
  });

  it('sends no DELETE when the patient cancels on the installed tab', async () => {
    const { client, remove } = writePort();
    const view = await render(<RootNavigator locale="en" deviceClient={client} />);
    await waitFor(() => expect(view.getByTestId('revoke-dev-1')).toBeTruthy());

    await fireEvent.press(view.getByTestId('revoke-dev-1'));
    expect(view.getByTestId('device-revocation-dev-1')).toBeTruthy();
    await fireEvent.press(view.getByTestId('device-revoke-cancel'));

    expect(view.queryByTestId('device-revocation-dev-1')).toBeNull();
    expect(view.getByTestId('revoke-dev-1')).toBeTruthy();
    expect(remove).not.toHaveBeenCalled();
  });

  it('sends one scoped DELETE and refreshes devices from the installed tab', async () => {
    const { client, remove } = writePort();
    const view = await render(<RootNavigator locale="en" deviceClient={client} />);
    await waitFor(() => expect(view.getByTestId('revoke-dev-1')).toBeTruthy());

    await fireEvent.press(view.getByTestId('revoke-dev-1'));
    expect(view.getByTestId('device-revocation-dev-1')).toBeTruthy();
    await fireEvent.press(view.getByTestId('device-revoke-confirm'));

    await waitFor(() => expect(remove).toHaveBeenCalledTimes(1));
    expect(remove).toHaveBeenCalledWith(
      '/api/v1/patient/appointments/devices/dev-1?patientId=synthetic-patient',
    );
    await waitFor(() => expect(view.queryByTestId('revoke-dev-1')).toBeNull());
  });
});
