/**
 * READY-33 acceptance tests for the minimum Expo entry (PAT-01, UX-01).
 *
 * The placeholder root navigator must present the application title in both
 * supported languages, the registered root component must be the application,
 * and the Expo entry point must register exactly that component.
 */
import { jest } from '@jest/globals';
import { render } from '@testing-library/react-native';
import { registerRootComponent } from 'expo';

import App from '../../App';
import '../../index';
import { appText } from './messages';
import { RootNavigator } from './RootNavigator';

jest.mock('expo', () => ({
  registerRootComponent: jest.fn(),
}));

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
