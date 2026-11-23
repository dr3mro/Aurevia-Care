import { act, render, screen, waitFor } from '@testing-library/react';
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

function wrap(view: ReactNode, options: { sessionExpired?: () => void; locale?: Locale } = {}) {
  return render(
    <ThemeProvider>
      <I18nProvider initialLocale={options.locale ?? 'en'}>
        <SessionContext.Provider
          value={{ onSessionExpired: options.sessionExpired ?? (() => undefined) }}
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
  });

  it('renders the Arabic loading state in right-to-left direction', () => {
    const gate = deferred<readonly string[]>();

    wrap(<ApiPage resource={resource(gate.promise)}>{children}</ApiPage>, { locale: 'ar' });

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByText('جار التحميل…')).toBeTruthy();
  });
});
