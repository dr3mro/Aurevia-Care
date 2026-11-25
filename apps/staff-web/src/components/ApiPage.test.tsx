import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { TransportError } from '../api/transport';
import type { TransportErrorKind } from '../api/transport';
import { I18nProvider } from '../i18n/I18nProvider';
import type { Locale } from '../i18n/messages';
import { ThemeProvider } from '../theme/ThemeProvider';
import { ApiPage } from './ApiPage';
import { SessionContext } from './SessionContext';

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((onResolve) => {
    resolve = onResolve;
  });
  return { promise, resolve };
}

function resource<T>(value: T | Promise<T>, deps: readonly unknown[] = []) {
  return { label: 'Synthetic rows', load: async () => value, deps };
}

function wrap(
  view: ReactNode,
  options: {
    sessionExpired?: () => void;
    onSignIn?: () => void;
    locale?: Locale;
  } = {},
) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={options.locale ?? 'en'}>
        <SessionContext.Provider
          value={{
            onSessionExpired: options.sessionExpired ?? (() => undefined),
            onSignIn: options.onSignIn ?? (() => undefined),
          }}
        >
          {view}
        </SessionContext.Provider>
      </I18nProvider>
    </ThemeProvider>,
  );
}

function withoutSessionProvider(view: ReactNode) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale="en">{view}</I18nProvider>
    </ThemeProvider>,
  );
}

const EXPECTED_TEXT: Record<TransportErrorKind, string> = {
  'csrf-missing': 'Something went wrong',
  'session-expired': 'Your session has expired',
  forbidden: 'You do not have access',
  'not-found': 'Something went wrong',
  conflict: 'This record changed',
  invalid: 'Something went wrong',
  'precondition-required': 'This record changed',
  'rate-limited': 'Something went wrong',
  server: 'Something went wrong',
  offline: 'Something went wrong',
  timeout: 'Something went wrong',
};

const ALL_KINDS = Object.keys(EXPECTED_TEXT) as TransportErrorKind[];

function failure(kind: TransportErrorKind) {
  return () => {
    return Promise.reject(
      new TransportError(kind, {
        correlationId: kind,
        code: kind,
        retryAfterSeconds: undefined,
      }),
    );
  };
}

function children(value: readonly string[]) {
  return <p data-testid="rows">{value.join(',')}</p>;
}

describe('ApiPage', () => {
  it('renders the loading state while the request is pending', () => {
    const gate = deferred<readonly string[]>();

    wrap(<ApiPage resource={resource(gate.promise)}>{children}</ApiPage>);

    const region = screen.getByRole('region', { name: 'Synthetic rows' });
    expect(region.textContent).toContain('Loading…');
    expect(screen.queryByTestId('rows')).toBeNull();
  });

  it('renders the loaded data once the request resolves', async () => {
    const gate = deferred<readonly string[]>();

    wrap(
      <ApiPage resource={resource(gate.promise)} isEmpty={() => false}>
        {children}
      </ApiPage>,
    );
    await act(async () => {
      gate.resolve(['alpha-1', 'alpha-2']);
      await gate.promise;
    });

    expect(screen.getByTestId('rows').textContent).toBe('alpha-1,alpha-2');
    expect(screen.queryByText('Loading…')).toBeNull();
  });

  it('renders the empty state when isEmpty accepts the data', async () => {
    wrap(
      <ApiPage resource={resource([] as readonly string[])} isEmpty={(value) => value.length === 0}>
        {children}
      </ApiPage>,
    );

    await waitFor(() => expect(screen.getByText('Nothing to show')).toBeTruthy());
    expect(screen.queryByTestId('rows')).toBeNull();
  });

  it('renders children when no isEmpty predicate is supplied', async () => {
    wrap(<ApiPage resource={resource([] as readonly string[])}>{children}</ApiPage>);

    await waitFor(() => expect(screen.getByTestId('rows').textContent).toBe(''));
  });

  it.each(ALL_KINDS)('maps the %s transport error to its required state view', async (kind) => {
    wrap(
      <ApiPage resource={{ label: 'Synthetic rows', load: failure(kind), deps: [] }}>
        {children}
      </ApiPage>,
    );

    await waitFor(() => expect(screen.getByText(EXPECTED_TEXT[kind])).toBeTruthy());
    expect(screen.queryByTestId('rows')).toBeNull();
  });

  it('calls onSessionExpired once when a session-expired error is shown', async () => {
    const onSessionExpired = vi.fn();
    const view = wrap(
      <ApiPage resource={{ label: 'Synthetic rows', load: failure('session-expired'), deps: [] }}>
        {children}
      </ApiPage>,
      { sessionExpired: onSessionExpired },
    );

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    view.rerender(
      <ThemeProvider>
        <I18nProvider initialLocale="en">
          <SessionContext.Provider value={{ onSessionExpired }}>
            <ApiPage
              resource={{ label: 'Synthetic rows', load: failure('session-expired'), deps: [] }}
            >
              {children}
            </ApiPage>
          </SessionContext.Provider>
        </I18nProvider>
      </ThemeProvider>,
    );
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('uses the no-op session callback when no provider is mounted', async () => {
    withoutSessionProvider(
      <ApiPage resource={{ label: 'Synthetic rows', load: failure('session-expired'), deps: [] }}>
        {children}
      </ApiPage>,
    );

    await waitFor(() => expect(screen.getByText('Your session has expired')).toBeTruthy());
    const signIn = screen.getByRole('button', { name: 'Sign in' });
    expect(signIn.tagName).toBe('BUTTON');
    expect(() => fireEvent.click(signIn)).not.toThrow();
  });
});

describe('ApiPage recovery actions', () => {
  it('re-runs the loader from useApi when Reload is clicked', async () => {
    const load = vi.fn(failure('conflict'));
    wrap(<ApiPage resource={{ label: 'Synthetic rows', load, deps: [] }}>{children}</ApiPage>);

    await waitFor(() => expect(screen.getByText('This record changed')).toBeTruthy());
    expect(load).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
  });

  it('uses the session onSignIn action without re-notifying expiry on click', async () => {
    const onSessionExpired = vi.fn();
    const onSignIn = vi.fn();
    wrap(
      <ApiPage resource={{ label: 'Synthetic rows', load: failure('session-expired'), deps: [] }}>
        {children}
      </ApiPage>,
      { sessionExpired: onSessionExpired, onSignIn },
    );

    await waitFor(() => expect(screen.getByText('Your session has expired')).toBeTruthy());
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(onSignIn).toHaveBeenCalledTimes(1);
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it('passes explicit Compare, Copy, Reload and Retry actions into the state views', async () => {
    const onCompare = vi.fn();
    const onCopy = vi.fn();
    const onReload = vi.fn();
    const onRetry = vi.fn();
    const actions = { onCompare, onCopy, onReload, onRetry };

    const conflict = wrap(
      <ApiPage
        resource={{ label: 'Synthetic rows', load: failure('conflict'), deps: [] }}
        actions={actions}
      >
        {children}
      </ApiPage>,
    );
    await waitFor(() => expect(screen.getByText('This record changed')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Compare' }));
    fireEvent.click(screen.getByRole('button', { name: 'Copy my changes' }));
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    expect(onCompare).toHaveBeenCalledTimes(1);
    expect(onCopy).toHaveBeenCalledTimes(1);
    expect(onReload).toHaveBeenCalledTimes(1);
    expect(onRetry).not.toHaveBeenCalled();
    conflict.unmount();

    wrap(
      <ApiPage
        resource={{ label: 'Synthetic rows', load: failure('server'), deps: [] }}
        actions={actions}
      >
        {children}
      </ApiPage>,
    );
    await waitFor(() => expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('prefers explicit onReload and onSignIn actions over the context fallbacks', async () => {
    const load = vi.fn(failure('conflict'));
    const sessionSignIn = vi.fn();
    const onReload = vi.fn();
    const onSignIn = vi.fn();
    const actions = { onReload, onSignIn };

    const conflict = wrap(
      <ApiPage resource={{ label: 'Synthetic rows', load, deps: [] }} actions={actions}>
        {children}
      </ApiPage>,
      { onSignIn: sessionSignIn },
    );
    await waitFor(() => expect(screen.getByText('This record changed')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
    expect(onReload).toHaveBeenCalledTimes(1);
    expect(load).toHaveBeenCalledTimes(1);
    conflict.unmount();

    wrap(
      <ApiPage
        resource={{ label: 'Synthetic rows', load: failure('session-expired'), deps: [] }}
        actions={actions}
      >
        {children}
      </ApiPage>,
      { onSignIn: sessionSignIn },
    );
    await waitFor(() => expect(screen.getByText('Your session has expired')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(onSignIn).toHaveBeenCalledTimes(1);
    expect(sessionSignIn).not.toHaveBeenCalled();
  });

  it('renders the Arabic loading state in right-to-left direction', () => {
    const gate = deferred<readonly string[]>();

    wrap(<ApiPage resource={resource(gate.promise)}>{children}</ApiPage>, { locale: 'ar' });

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText('جار التحميل…')).toBeTruthy();
  });
});
