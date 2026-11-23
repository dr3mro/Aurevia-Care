import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import type { TransportError } from '../api/transport';
import { useApi } from '../api/useApi';
import type { ApiLoader } from '../api/useApi';
import { useBreakpoint } from '../theme/useBreakpoint';
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
 *
 * Every state shares one labelled `page` region. That class owns the maximum
 * reading width, the logical safe-area padding and the single-column grid that
 * lets long values wrap (WEB-07, UX-01); the resolved layout class is exposed
 * as `data-breakpoint` so structure can be asserted in jsdom, where media
 * queries cannot be evaluated. Direction stays with the document — no subtree
 * overrides it, so the same markup mirrors under right-to-left.
 */
export function ApiPage<T>({ resource, children, isEmpty }: ApiPageProps<T>) {
  const result = useApi<T>(resource.load, resource.deps);
  const breakpoint = useBreakpoint();
  const session = useSession();
  const handledError = useRef<TransportError | undefined>(undefined);

  // The latest callback is read on every run, while the error identity is the
  // deduplication key, so StrictMode's second effect run cannot notify twice.
  useEffect(() => {
    if (result.status === 'error') {
      if (handledError.current !== result.error) {
        handledError.current = result.error;
        if (result.error.kind === 'session-expired') {
          session.onSessionExpired();
        }
      }
    } else {
      handledError.current = undefined;
    }
  });

  if (result.status === 'loading') {
    return (
      <section className="page" data-breakpoint={breakpoint} aria-label={resource.label}>
        <LoadingState />
      </section>
    );
  }
  if (result.status === 'error') {
    return (
      <section className="page" data-breakpoint={breakpoint} aria-label={resource.label}>
        {StateView.forTransportError(result.error)}
      </section>
    );
  }
  const data = result.data;
  if (isEmpty?.(data) === true) {
    return (
      <section className="page" data-breakpoint={breakpoint} aria-label={resource.label}>
        <EmptyState />
      </section>
    );
  }
  return (
    <section className="page" data-breakpoint={breakpoint} aria-label={resource.label}>
      {children(data)}
    </section>
  );
}
