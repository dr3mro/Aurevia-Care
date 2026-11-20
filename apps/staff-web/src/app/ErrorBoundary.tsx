import { Component } from 'react';
import type { ReactNode } from 'react';
import { ErrorState } from '../components/StateViews';

/** Props for the application-wide render-error boundary. */
export interface ErrorBoundaryProps {
  children: ReactNode;
}

/** State carried after a render failure: an opaque ID and the failed flag. */
export interface ErrorBoundaryState {
  correlationId: string;
  failed: boolean;
}

/**
 * Catches render errors anywhere below it and presents a safe error state.
 * Only the generated correlation ID and the error's class name are logged;
 * component props, state and error text can contain clinical data and are
 * deliberately never passed to `console.error` (WEB-06, WEB-09).
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { correlationId: '', failed: false };

  /** Generate the correlation ID before the failing tree is replaced. */
  static getDerivedStateFromError(): ErrorBoundaryState {
    return { correlationId: crypto.randomUUID(), failed: true };
  }

  /** Log a single, content-free line for the captured failure. */
  componentDidCatch(error: Error): void {
    console.error(`${this.state.correlationId} ${error.name}`);
  }

  /** Clear the failure state so an explicit Retry remounts the healthy tree. */
  private readonly retry = (): void => {
    this.setState({ correlationId: '', failed: false });
  };

  render(): ReactNode {
    if (this.state.failed) {
      return <ErrorState correlationId={this.state.correlationId} onRetry={this.retry} />;
    }
    return this.props.children;
  }
}
