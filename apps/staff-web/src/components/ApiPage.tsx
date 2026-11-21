import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import type { TransportError } from '../api/transport';
import { useApi } from '../api/useApi';
import type { ApiLoader } from '../api/useApi';
import { useSession } from './SessionContext';
import { EmptyState, LoadingState, StateView } from './StateViews';

/** A loader and the values that identify one request for the page. */
export interface ApiResource<T> {
  /** Accessible region name; callers pass a message in the active locale. */
  label: string;
  load: ApiLoader<T>;
  deps: readonly unknown[];
}

/** Props for the shared data-loading page wrapper. */
export interface ApiPageProps<T> {
  resource: ApiResource<T>;
  children: (data: T) => ReactNode;
  isEmpty?: ((data: T) => boolean) | undefined;
}

/**
 * Render the required screen states for one resource (WEB-09) from a single
 * `useApi` call: loading, empty, a mapped transport error, or the loaded data.
 * A `session-expired` failure is reported once to the owning `SessionContext`
 * for the exact error instance, so a re-render never repeats the notification.
 */
export function ApiPage<T>({ resource, children, isEmpty }: ApiPageProps<T>) {
  const result = useApi<T>(resource.load, resource.deps);
  const session = useSession();
  const onSessionExpired = useRef(session.onSessionExpired);
  onSessionExpired.current = session.onSessionExpired;
  const handledError = useRef<TransportError | undefined>(undefined);

  // The latest callback is read from a ref, while the error identity is the
  // deduplication key, so StrictMode's second effect run cannot notify twice.
  useEffect(() => {
    if (result.status === 'error') {
      if (handledError.current !== result.error) {
        handledError.current = result.error;
        if (result.error.kind === 'session-expired') {
          onSessionExpired.current();
        }
      }
    } else {
      handledError.current = undefined;
    }
  });

  if (result.status === 'loading') {
    return (
      <section aria-label={resource.label}>
        <LoadingState />
      </section>
    );
  }
  if (result.status === 'error') {
    return (
      <section aria-label={resource.label}>
        {StateView.forTransportError(result.error)}
      </section>
    );
  }
  const data = result.data;
  if (isEmpty?.(data) === true) {
    return (
      <section aria-label={resource.label}>
        <EmptyState />
      </section>
    );
  }
  return <section aria-label={resource.label}>{children(data)}</section>;
}
