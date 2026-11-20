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
import { appText } from './messages';
import { RootNavigator } from './RootNavigator';
import { TAB_DEFINITIONS } from './tabs';

jest.mock('expo', () => ({
  registerRootComponent: jest.fn(),
}));

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

    expect(view.getByTestId('appointment-list')).toBeTruthy();
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
