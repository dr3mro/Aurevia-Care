import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ActiveOrganizationProvider } from '../../src/api/activeOrganization';
import { TransportError } from '../../src/api/transport';
import type { TransportErrorKind } from '../../src/api/transport';
import { useApi } from '../../src/api/useApi';
import { must } from '../must';

const ALPHA = '11111111-1111-1111-1111-111111111111';
const BETA = '22222222-2222-2222-2222-222222222222';

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
}

/** A manually controlled promise so timing of responses is deterministic. */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}

async function settle<T>(gate: Deferred<T>, value: T): Promise<void> {
  await act(async () => {
    gate.resolve(value);
    await gate.promise;
  });
}

type Loader = (signal: AbortSignal) => Promise<string>;

function Harness({ load, deps }: { load: Loader; deps: readonly unknown[] }) {
  const result = useApi<string>(load, deps);
  return (
    <div>
      <span data-testid="status">{result.status}</span>
      <span data-testid="data">{result.data ?? 'none'}</span>
      <span data-testid="error">{result.error?.kind ?? 'none'}</span>
      <button type="button" onClick={result.reload}>
        reload
      </button>
    </div>
  );
}

function renderHarness(load: Loader, deps: readonly unknown[], organization = ALPHA) {
  return render(
    <ActiveOrganizationProvider value={{ organizationId: organization }}>
      <Harness load={load} deps={deps} />
    </ActiveOrganizationProvider>,
  );
}

function withOrganization(organization: string, deps: readonly unknown[], load: Loader) {
  return (
    <ActiveOrganizationProvider value={{ organizationId: organization }}>
      <Harness load={load} deps={deps} />
    </ActiveOrganizationProvider>
  );
}

function status(): string | null {
  return screen.getByTestId('status').textContent;
}

function data(): string | null {
  return screen.getByTestId('data').textContent;
}

function errorKind(): string | null {
  return screen.getByTestId('error').textContent;
}

const ALL_KINDS: readonly TransportErrorKind[] = [
  'csrf-missing',
  'session-expired',
  'forbidden',
  'not-found',
  'conflict',
  'invalid',
  'precondition-required',
  'rate-limited',
  'server',
  'offline',
  'timeout',
];

function errorDetails(kind: TransportErrorKind) {
  return { correlationId: kind, code: kind, retryAfterSeconds: undefined };
}

describe('useApi', () => {
  it('moves from loading to ready with the loaded data', async () => {
    const gate = deferred<string>();
    const load = vi.fn<Loader>(() => gate.promise);

    renderHarness(load, ['range']);

    expect(status()).toBe('loading');
    expect(data()).toBe('none');
    await settle(gate, 'alpha-rows');
    expect(status()).toBe('ready');
    expect(data()).toBe('alpha-rows');
    expect(load).toHaveBeenCalledTimes(1);
  });

  it.each(ALL_KINDS)('preserves the %s transport error kind', async (kind) => {
    const load = vi.fn<Loader>(() => Promise.reject(new TransportError(kind, errorDetails(kind))));

    renderHarness(load, []);

    await waitFor(() => expect(status()).toBe('error'));
    expect(errorKind()).toBe(kind);
    expect(data()).toBe('none');
  });

  it('aborts the in-flight request when the component unmounts', async () => {
    const gate = deferred<string>();
    let captured: AbortSignal | undefined;
    const load = vi.fn<Loader>((signal) => {
      captured = signal;
      return gate.promise;
    });

    const view = renderHarness(load, []);
    await waitFor(() => expect(captured).toBeDefined());
    expect(captured?.aborted).toBe(false);

    view.unmount();
    expect(captured?.aborted).toBe(true);

    // A response that arrives after unmount must not produce a state update.
    await act(async () => {
      gate.resolve('late');
      await gate.promise;
    });
  });

  it('ignores a late resolved response from a request aborted by a deps change', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const signals: AbortSignal[] = [];
    const load = vi.fn<Loader>((signal) => {
      signals.push(signal);
      return signals.length === 1 ? first.promise : second.promise;
    });

    const view = renderHarness(load, ['first']);
    await waitFor(() => expect(signals.length).toBe(1));

    view.rerender(withOrganization(ALPHA, ['second'], load));
    await waitFor(() => expect(signals.length).toBe(2));
    expect(signals[0]?.aborted).toBe(true);

    await settle(first, 'stale-rows');
    expect(status()).toBe('loading');
    expect(data()).toBe('none');

    await settle(second, 'fresh-rows');
    expect(data()).toBe('fresh-rows');
  });

  it('ignores a late rejection from a request aborted by a deps change', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const signals: AbortSignal[] = [];
    const load = vi.fn<Loader>((signal) => {
      signals.push(signal);
      return signals.length === 1 ? first.promise : second.promise;
    });

    const view = renderHarness(load, ['first']);
    await waitFor(() => expect(signals.length).toBe(1));

    view.rerender(withOrganization(ALPHA, ['second'], load));
    await waitFor(() => expect(signals.length).toBe(2));

    await act(async () => {
      first.reject(new TransportError('server', errorDetails('server')));
      await first.promise.catch(() => undefined);
    });
    expect(status()).toBe('loading');
    expect(errorKind()).toBe('none');

    await settle(second, 'ok');
    expect(data()).toBe('ok');
  });

  it('clears the previous organization data before the new organization responds', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const signals: AbortSignal[] = [];
    const load = vi.fn<Loader>((signal) => {
      signals.push(signal);
      return signals.length === 1 ? first.promise : second.promise;
    });

    const view = renderHarness(load, ['range'], ALPHA);
    await settle(first, 'alpha-rows');
    expect(data()).toBe('alpha-rows');

    view.rerender(withOrganization(BETA, ['range'], load));

    expect(signals[0]?.aborted).toBe(true);
    expect(status()).toBe('loading');
    expect(data()).toBe('none');

    await settle(second, 'beta-rows');
    expect(data()).toBe('beta-rows');
  });

  it('aborts the current request and refetches when reload is called', async () => {
    const gates = [deferred<string>(), deferred<string>()];
    const signals: AbortSignal[] = [];
    const load = vi.fn<Loader>((signal) => {
      signals.push(signal);
      return must(gates[signals.length - 1]).promise;
    });

    renderHarness(load, []);
    await settle(must(gates[0]), 'first');
    expect(data()).toBe('first');

    fireEvent.click(screen.getByRole('button', { name: 'reload' }));
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
    expect(signals[0]?.aborted).toBe(true);
    expect(status()).toBe('loading');

    await settle(must(gates[1]), 'second');
    expect(data()).toBe('second');
  });

  it('normalises an unexpected rejection into a server transport error', async () => {
    const load = vi.fn<Loader>(() => Promise.reject(new Error('unexpected failure')));

    renderHarness(load, []);

    await waitFor(() => expect(status()).toBe('error'));
    expect(errorKind()).toBe('server');
  });

  it('does not refetch when a new deps array holds the same values', async () => {
    const gate = deferred<string>();
    const load = vi.fn<Loader>(() => gate.promise);

    const view = renderHarness(load, ['same']);
    await settle(gate, 'rows');

    view.rerender(withOrganization(ALPHA, ['same'], load));

    expect(load).toHaveBeenCalledTimes(1);
    expect(data()).toBe('rows');
  });

  it('uses the synthetic default organization when no provider is mounted', async () => {
    const gate = deferred<string>();
    const load = vi.fn<Loader>(() => gate.promise);

    render(<Harness load={load} deps={[]} />);
    await settle(gate, 'default-rows');

    expect(data()).toBe('default-rows');
  });
});
