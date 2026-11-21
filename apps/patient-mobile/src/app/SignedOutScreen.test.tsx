/**
 * READY-48 acceptance tests for the signed-out destination (PAT-03, WEB-09).
 * All text comes from the application message dictionary in both locales.
 */
import { jest } from '@jest/globals';
import { fireEvent, render } from '@testing-library/react-native';

import { appText } from './messages';
import { SignedOutScreen } from './SignedOutScreen';

describe('SignedOutScreen', () => {
  it.each(['en', 'ar'] as const)('shows the localized session message in %s', async (locale) => {
    const view = await render(<SignedOutScreen locale={locale} onSignIn={() => undefined} />);
    expect(view.getByTestId('signed-out-screen')).toBeTruthy();
    expect(view.getByTestId('signed-out-title')).toHaveTextContent(appText(locale, 'signedOutTitle'));
    expect(view.getByTestId('signed-out-body')).toHaveTextContent(appText(locale, 'signedOutBody'));
  });

  it('offers an accessible sign-in action', async () => {
    const onSignIn = jest.fn();
    const view = await render(<SignedOutScreen locale="en" onSignIn={onSignIn} />);
    const control = view.getByTestId('sign-in-action');
    expect(control.props.accessibilityRole).toBe('button');
    expect(control.props.accessibilityLabel).toBe(appText('en', 'signIn'));
    await fireEvent.press(control);
    expect(onSignIn).toHaveBeenCalledTimes(1);
  });
});
