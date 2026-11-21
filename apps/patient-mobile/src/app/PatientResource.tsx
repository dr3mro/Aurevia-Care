/**
 * One loading boundary for every patient data screen (PAT-01–03, WEB-09).
 *
 * The hook-free component owns a single request at a time, discards late
 * responses after unmount, maps every transport failure to a shared state and
 * keeps no clinical data between requests. Retry reissues the same loader.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';

import { asTransportError } from '../api/transport';

import { ScreenStateView, screenStateForError } from './screenStates';
import type { ResourceState, ScreenMessages } from './screenStates';

/** Props for {@link PatientResource}. */
export interface PatientResourceProps<T> {
  /** Issue one request; recreated by the caller when its inputs change. */
  readonly load: () => Promise<T>;
  /** Decide whether a successful response has no rows to show. */
  readonly isEmpty: (data: T) => boolean;
  /** Screen-specific empty explanation. */
  readonly emptyMessage: string;
  /** Localized state labels. */
  readonly messages: ScreenMessages;
  /** Handles a 401 by clearing the credential and leaving the signed-in shell. */
  readonly onSessionExpired: () => void;
  /** Render validated data in the ready state. */
  readonly children: (data: T) => ReactNode;
}

/**
 * Load one patient resource and present every required screen state.
 *
 * @param props loader, state labels, callbacks and ready-state renderer
 * @returns the current state view or the loaded screen
 */
export function PatientResource<T>({
  load,
  isEmpty,
  emptyMessage,
  messages,
  onSessionExpired,
  children,
}: PatientResourceProps<T>): React.JSX.Element {
  const [state, setState] = useState<ResourceState<T>>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    load()
      .then((data) => {
        if (active) {
          setState(isEmpty(data) ? { kind: 'empty' } : { kind: 'ready', data });
        }
      })
      .catch((error: unknown) => {
        if (!active) {
          return;
        }
        const failure = asTransportError(error, 'network');
        if (failure.kind === 'unauthenticated') {
          onSessionExpired();
        }
        setState(screenStateForError(failure));
      });
    return () => {
      active = false;
    };
  }, [load, isEmpty, onSessionExpired, attempt]);

  const retry = useCallback(() => {
    setState({ kind: 'loading' });
    setAttempt((value) => value + 1);
  }, []);

  return (
    <ScreenStateView
      state={state}
      messages={messages}
      emptyMessage={emptyMessage}
      onRetry={retry}>
      {children}
    </ScreenStateView>
  );
}
