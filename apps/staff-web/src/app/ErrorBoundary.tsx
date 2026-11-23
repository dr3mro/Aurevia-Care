import type { ReactNode } from 'react';
import { ErrorBoundary as LibraryErrorBoundary } from 'react-error-boundary';
import { ErrorState } from '../components/StateViews';

/** Props for the application-wide render-error boundary. */
export interface ErrorBoundaryProps {
  children: ReactNode;
}

/** One anonymised failure: an opaque ID and the thrown value's type name. */
interface FailureRecord {
  correlationId: string;
  errorName: string;
}

/**
 * React discards in-progress hook state when a descendant throws and replays
 * the render, so a ref inside the wrapper cannot hold the ID. There is exactly
 * one global boundary and at most one active failure below it, so a single
 * module-level record is sufficient and keeps one ID and one log line per
 * failure across that replay.
 */
let activeFailure: FailureRecord | null = null;

/** Return the active record, creating and logging it once on first sight. */
function recordFor(error: unknown): FailureRecord {
  if (activeFailure === null) {
    activeFailure = {
      correlationId: crypto.randomUUID(),
      errorName: error instanceof Error ? error.name : 'Error',
    };
    // Only the opaque ID and the type name: props, state and error text may
    // hold clinical data and are never logged (WEB-06, WEB-09).
    globalThis.console.error(`${activeFailure.correlationId} ${activeFailure.errorName}`);
  }
  return activeFailure;
}

/** Clear the active failure after recovery or an explicit boundary reset. */
export function clearRenderFailure(): void {
  activeFailure = null;
}

/**
 * Catches render errors anywhere below it and presents a safe error state.
 * The reviewed `react-error-boundary` dependency owns the class-based catcher;
 * this module is a function component, as the frontend policy requires.
 */
export function ErrorBoundary({ children }: ErrorBoundaryProps): ReactNode {
  return (
    <LibraryErrorBoundary
      fallbackRender={({ error, resetErrorBoundary }) => {
        const failure = recordFor(error);
        return (
          <ErrorState
            correlationId={failure.correlationId}
            onRetry={() => {
              clearRenderFailure();
              resetErrorBoundary();
            }}
          />
        );
      }}
    >
      {children}
    </LibraryErrorBoundary>
  );
}
