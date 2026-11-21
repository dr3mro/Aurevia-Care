/**
 * READY-48 acceptance tests for the generic patient resource loader (PAT-01–03,
 * WEB-09). The fake loader stands in for the API transport and each state is
 * asserted through the public screen contract.
 */
import { jest } from '@jest/globals';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { Text } from 'react-native';

import { TransportError } from '../api/transport';

import { PatientResource } from './PatientResource';
import { screenMessages } from './screenStates';

const messages = screenMessages('en');

/** Stable emptiness predicate so a re-render never reissues the request. */
function isEmptyRows(rows: readonly string[]): boolean {
  return rows.length === 0;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('PatientResource', () => {
  it('shows loading and then the loaded rows', async () => {
    const pending = deferred<string[]>();
    const view = await render(
      <PatientResource
        load={() => pending.promise}
        isEmpty={isEmptyRows}
        emptyMessage="Nothing yet"
        messages={messages}
        onSessionExpired={() => undefined}>
        {(rows) => <Text>{rows.join(',')}</Text>}
      </PatientResource>,
    );
    expect(view.getByTestId('screen-loading')).toBeTruthy();
    await act(async () => {
      pending.resolve(['one', 'two']);
      await pending.promise;
    });
    await waitFor(() => expect(view.getByText('one,two')).toBeTruthy());
  });

  it('shows the empty message when the loaded list has no rows', async () => {
    const view = await render(
      <PatientResource
        load={async () => []}
        isEmpty={isEmptyRows}
        emptyMessage="No appointments"
        messages={messages}
        onSessionExpired={() => undefined}>
        {(rows) => <Text>{rows.join(',')}</Text>}
      </PatientResource>,
    );
    await waitFor(() => expect(view.getByTestId('screen-empty')).toBeTruthy());
    expect(view.getByTestId('screen-empty')).toHaveTextContent('No appointments');
  });

  it('shows the error state with a correlation id and retries on demand', async () => {
    const load = jest.fn(async () => {
      throw new TransportError('server', { correlationId: 'corr-9' });
    });
    const view = await render(
      <PatientResource
        load={load}
        isEmpty={isEmptyRows}
        emptyMessage="Nothing yet"
        messages={messages}
        onSessionExpired={() => undefined}>
        {(rows) => <Text>{rows.join(',')}</Text>}
      </PatientResource>,
    );
    await waitFor(() => expect(view.getByTestId('screen-error')).toBeTruthy());
    expect(view.getByTestId('screen-correlation')).toHaveTextContent(
      /corr-9/,
    );
    await fireEvent.press(view.getByTestId('screen-retry'));
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
  });

  it('maps an unknown thrown value to the offline state', async () => {
    const view = await render(
      <PatientResource
        load={async () => {
          throw new Error('socket closed');
        }}
        isEmpty={isEmptyRows}
        emptyMessage="Nothing yet"
        messages={messages}
        onSessionExpired={() => undefined}>
        {(rows) => <Text>{rows.join(',')}</Text>}
      </PatientResource>,
    );
    await waitFor(() => expect(view.getByTestId('screen-offline')).toBeTruthy());
  });

  it('calls onSessionExpired once when the transport reports 401', async () => {
    const onSessionExpired = jest.fn();
    const view = await render(
      <PatientResource
        load={async () => {
          throw new TransportError('unauthenticated', {});
        }}
        isEmpty={isEmptyRows}
        emptyMessage="Nothing yet"
        messages={messages}
        onSessionExpired={onSessionExpired}>
        {(rows) => <Text>{rows.join(',')}</Text>}
      </PatientResource>,
    );
    await waitFor(() => expect(view.getByTestId('screen-session-expired')).toBeTruthy());
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('ignores a late success after the component has unmounted', async () => {
    const pending = deferred<string[]>();
    const load = () => pending.promise;
    const renderRows = jest.fn((rows: string[]) => <Text>{rows.join(',')}</Text>);
    const view = await render(
      <PatientResource
        load={load}
        isEmpty={isEmptyRows}
        emptyMessage="Nothing yet"
        messages={messages}
        onSessionExpired={() => undefined}>
        {renderRows}
      </PatientResource>,
    );
    await act(async () => {
      await view.unmount();
    });
    await act(async () => {
      pending.resolve(['late']);
      await pending.promise;
    });
    expect(renderRows).not.toHaveBeenCalled();
  });

  it('ignores a late failure after the component has unmounted', async () => {
    const pending = deferred<string[]>();
    const onSessionExpired = jest.fn();
    const load = () => pending.promise;
    const view = await render(
      <PatientResource
        load={load}
        isEmpty={isEmptyRows}
        emptyMessage="Nothing yet"
        messages={messages}
        onSessionExpired={onSessionExpired}>
        {(rows) => <Text>{rows.join(',')}</Text>}
      </PatientResource>,
    );
    await act(async () => {
      await view.unmount();
    });
    await act(async () => {
      pending.reject(new TransportError('unauthenticated', {}));
      await pending.promise.catch(() => undefined);
    });
    expect(onSessionExpired).not.toHaveBeenCalled();
  });
});
