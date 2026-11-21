/**
 * READY-48 acceptance tests for the shared patient screen states (PAT-01–03,
 * WEB-09). Every visible label is checked against both supported locales and
 * every failure kind is mapped to one of the five required states.
 */
import { jest } from '@jest/globals';
import { fireEvent, render } from '@testing-library/react-native';
import { Text } from 'react-native';

import { TransportError } from '../api/transport';

import { appText } from './messages';
import { screenMessages, screenStateForError, ScreenStateView } from './screenStates';
import type { ScreenMessages } from './screenStates';

const labels: ScreenMessages = {
  locale: 'en',
  loading: 'Loading…',
  error: 'Something went wrong',
  offline: 'You appear to be offline',
  sessionExpired: 'Your session has expired',
  retry: 'Retry',
  signIn: 'Sign in',
  reference: 'Reference:',
};

describe('screenMessages', () => {
  it('provides every label in both supported languages', () => {
    const english = screenMessages('en');
    const arabic = screenMessages('ar');
    for (const key of Object.keys(english) as (keyof ScreenMessages)[]) {
      expect(english[key]).toBeTruthy();
      expect(arabic[key]).toBeTruthy();
    }
    expect(arabic.loading).not.toBe(english.loading);
  });

  it.each(['en', 'ar'] as const)('carries the active locale and localized reference prefix in %s', (locale) => {
    const messages = screenMessages(locale);
    expect(messages.locale).toBe(locale);
    expect(messages.reference).toBe(appText(locale, 'referencePrefix'));
  });
});

describe('screenStateForError', () => {
  it('maps unauthenticated to the session-expired state', () => {
    expect(screenStateForError(new TransportError('unauthenticated', {}))).toEqual({
      kind: 'session-expired',
      error: expect.any(TransportError),
    });
  });

  it.each(['network', 'timeout'] as const)('maps %s to the offline state', (kind) => {
    expect(screenStateForError(new TransportError(kind, {}))).toEqual({
      kind: 'offline',
      error: expect.any(TransportError),
    });
  });

  it.each(['forbidden', 'conflict', 'validation', 'server'] as const)(
    'maps %s to the generic error state with its correlation id',
    (kind) => {
      const error = new TransportError(kind, { correlationId: 'corr-1' });
      expect(screenStateForError(error)).toEqual({ kind: 'error', error });
    },
  );
});

describe('ScreenStateView', () => {
  it('announces the loading state politely', async () => {
    const view = await render(
      <ScreenStateView state={{ kind: 'loading' }} messages={labels} emptyMessage="Nothing yet">
        {() => null}
      </ScreenStateView>,
    );
    expect(view.getByTestId('screen-loading')).toBeTruthy();
    expect(view.getByTestId('screen-loading').props.accessibilityLiveRegion).toBe('polite');
  });

  it('shows the supplied empty message', async () => {
    const view = await render(
      <ScreenStateView state={{ kind: 'empty' }} messages={labels} emptyMessage="No appointments">
        {() => null}
      </ScreenStateView>,
    );
    expect(view.getByTestId('screen-empty')).toHaveTextContent('No appointments');
  });

  it('renders loaded data only in the ready state', async () => {
    const view = await render(
      <ScreenStateView state={{ kind: 'ready', data: 7 }} messages={labels} emptyMessage="Nothing yet">
        {(data) => <Text>{`count ${data}`}</Text>}
      </ScreenStateView>,
    );
    expect(view.getByText('count 7')).toBeTruthy();
    expect(view.queryByTestId('screen-loading')).toBeNull();
  });

  it('shows the correlation id and a retry control on an error', async () => {
    const onRetry = jest.fn();
    const view = await render(
      <ScreenStateView
        state={{ kind: 'error', error: new TransportError('server', { correlationId: 'corr-7' }) }}
        messages={labels}
        emptyMessage="Nothing yet"
        onRetry={onRetry}>
        {() => null}
      </ScreenStateView>,
    );
    expect(view.getByText(labels.error)).toBeTruthy();
    expect(view.getByTestId('screen-correlation')).toHaveTextContent(
      /corr-7/,
    );
    await fireEvent.press(view.getByTestId('screen-retry'));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('omits the correlation id when the transport did not supply one', async () => {
    const view = await render(
      <ScreenStateView
        state={{ kind: 'error', error: new TransportError('server', {}) }}
        messages={labels}
        emptyMessage="Nothing yet">
        {() => null}
      </ScreenStateView>,
    );
    expect(view.queryByTestId('screen-correlation')).toBeNull();
  });

  it('offers a retry control while offline', async () => {
    const onRetry = jest.fn();
    const view = await render(
      <ScreenStateView
        state={{ kind: 'offline', error: new TransportError('network', {}) }}
        messages={labels}
        emptyMessage="Nothing yet"
        onRetry={onRetry}>
        {() => null}
      </ScreenStateView>,
    );
    expect(view.getByText(labels.offline)).toBeTruthy();
    await fireEvent.press(view.getByTestId('screen-retry'));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('hides the retry control when no retry callback is supplied', async () => {
    const view = await render(
      <ScreenStateView
        state={{ kind: 'offline', error: new TransportError('network', {}) }}
        messages={labels}
        emptyMessage="Nothing yet">
        {() => null}
      </ScreenStateView>,
    );
    expect(view.getByTestId('screen-offline')).toBeTruthy();
    expect(view.queryByTestId('screen-retry')).toBeNull();
  });

  it('presents session expiry as an alert', async () => {
    const view = await render(
      <ScreenStateView
        state={{ kind: 'session-expired', error: new TransportError('unauthenticated', {}) }}
        messages={labels}
        emptyMessage="Nothing yet">
        {() => null}
      </ScreenStateView>,
    );
    expect(view.getByTestId('screen-session-expired').props.accessibilityRole).toBe('alert');
    expect(view.getByTestId('screen-session-expired')).toHaveTextContent(labels.sessionExpired);
  });
});
