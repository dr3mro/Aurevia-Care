import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useActiveOrganization } from './activeOrganization';
import { TransportError } from './transport';

/** A loader receives an abort signal and resolves with the typed resource. */
export type ApiLoader<T> = (signal: AbortSignal) => Promise<T>;

/**
 * The observable state of one request. The discriminant makes `data` present
 * only when ready and `error` present only when failed, so callers never need
 * a cast or an impossible "loading without data" branch.
 */
export type ApiResult<T> =
  | { status: 'loading'; data: undefined; error: undefined; reload: () => void }
  | { status: 'ready'; data: T; error: undefined; reload: () => void }
  | { status: 'error'; data: undefined; error: TransportError; reload: () => void };

type RequestState<T> =
  | { key: string; status: 'loading' }
  | { key: string; status: 'ready'; data: T }
  | { key: string; status: 'error'; error: TransportError };

/** Identify one request by tenant, caller dependencies and reload generation. */
function requestKey(organizationId: string, depsKey: string, generation: number): string {
  return `${organizationId}\u0000${depsKey}\u0000${generation}`;
}

/** Normalise any rejection to the typed error the state views can present. */
function asTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/**
 * Load one tenant-scoped resource with abortable, request-keyed state.
 *
 * The returned data belongs to the current active organization and the current
 * dependency values. A change to either clears the data during render, before
 * the replacement request can respond, so rows from a previous organization
 * are never painted. Each superseded request is aborted and a late response
 * from an aborted request is ignored. No result is cached or written to
 * browser storage.
 *
 * @param load Receives an `AbortSignal` and resolves with the resource.
 * @param deps Values that identify the request; a change refetches it.
 * @returns The current status, data or typed error, and a stable `reload`.
 */
export function useApi<T>(load: ApiLoader<T>, deps: readonly unknown[]): ApiResult<T> {
  const organizationId = useActiveOrganization();
  const depsKey = JSON.stringify(deps);
  const [generation, setGeneration] = useState(0);
  const key = requestKey(organizationId, depsKey, generation);
  const [state, setState] = useState<RequestState<T>>(() => ({ key, status: 'loading' }));
  const loader = useRef(load);

  // Commit the new key together with a cleared result while rendering; the key
  // is never updated on its own, so a stale result can never match the current
  // key, even when the dependencies return to an earlier value.
  if (state.key !== key) {
    setState({ key, status: 'loading' });
  }

  // Keep the latest loader in a ref without writing to it during render.
  useLayoutEffect(() => {
    loader.current = load;
  });

  useEffect(() => {
    const controller = new AbortController();
    loader.current(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) {
          setState({ key, status: 'ready', data });
        }
      },
      (failure: unknown) => {
        if (!controller.signal.aborted) {
          setState({ key, status: 'error', error: asTransportError(failure) });
        }
      },
    );
    return () => controller.abort();
  }, [key]);

  const reload = useCallback(() => setGeneration((value) => value + 1), [setGeneration]);

  if (state.key !== key || state.status === 'loading') {
    return { status: 'loading', data: undefined, error: undefined, reload };
  }
  if (state.status === 'ready') {
    return { status: 'ready', data: state.data, error: undefined, reload };
  }
  return { status: 'error', data: undefined, error: state.error, reload };
}
