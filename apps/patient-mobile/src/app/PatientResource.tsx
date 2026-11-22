/**
 * One loading boundary for every patient data screen (PAT-01–03, WEB-09).
 *
 * The component owns a single request at a time, discards late responses after
 * unmount, maps every transport failure to a shared state and keeps no clinical
 * data between requests. Retry reissues the same loader.
 *
 * Its output sits in a presentational, responsive container (PAT-01, PAT-02,
 * UX-01, decision 0005): the current window selects a single column or a
 * two-pane row, content is capped and centred on large windows, and safe-area
 * insets are applied as logical padding so the layout mirrors under
 * right-to-left without physical side properties. The container adds no data,
 * cache, navigation, text or API behaviour, and it has no accessibility props,
 * so state-view roles, labels and focus order are unchanged.
 */
import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { I18nManager, useWindowDimensions, View } from 'react-native';
import type { ViewStyle } from 'react-native';
import * as SafeArea from 'react-native-safe-area-context';

import { asTransportError } from '../api/transport';

import { windowClassFor } from './layout/useWindowClass';
import { ScreenStateView, screenStateForError } from './screenStates';
import type { ResourceState, ScreenMessages } from './screenStates';

/** Maximum readable width of patient content on a large window, in dp. */
const MAX_READABLE_WIDTH = 1200;

/** Minimum touch target in points from the native accessibility contract. */
const MIN_TOUCH_TARGET = 44;

/** Physical safe-area edges, in points, as returned by the safe-area library. */
interface SafeAreaEdges {
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
  readonly left: number;
}

/**
 * Resolve the safe-area inset hook once for the current module.
 *
 * The real package exposes `useSafeAreaInsets` as a named export. The app's
 * headless Jest mock returns the library's CommonJS mock module, which exposes
 * the same implementation under `default`; both shapes are supported so the
 * production path and the component tests read the same inset values. The
 * chosen function is stable for the lifetime of the module, so hook order does
 * not change between renders.
 */
const readSafeAreaInsets: () => SafeAreaEdges =
  SafeArea.useSafeAreaInsets ??
  (SafeArea as unknown as { default: { useSafeAreaInsets: () => SafeAreaEdges } }).default
    .useSafeAreaInsets;

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
 * @returns the responsive container holding the current state view or loaded screen
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

  /**
   * The window drives layout, never the device (decision 0005). On a large
   * window content is capped, centred and laid out as a row so a list and its
   * detail pane sit side by side; narrower windows keep a single column.
   * Safe-area values are physical, so the inline pair is swapped for
   * right-to-left while the style itself stays logical. No fixed height, cap
   * or line count is set, so text reflows at 200% without clipping.
   */
  const { width } = useWindowDimensions();
  const insets = readSafeAreaInsets();
  const isLarge = windowClassFor(width) === 'large';
  const isRtl = I18nManager.isRTL;
  const contentStyle: ViewStyle = {
    width: '100%',
    maxWidth: isLarge ? MAX_READABLE_WIDTH : undefined,
    alignSelf: isLarge ? 'center' : 'stretch',
    flexDirection: isLarge ? 'row' : 'column',
    justifyContent: isLarge ? 'center' : 'flex-start',
    minHeight: MIN_TOUCH_TARGET,
    paddingTop: insets.top,
    paddingBottom: insets.bottom,
    paddingStart: isRtl ? insets.right : insets.left,
    paddingEnd: isRtl ? insets.left : insets.right,
  };

  return (
    <View testID="patient-resource-layout" style={contentStyle}>
      <ScreenStateView
        state={state}
        messages={messages}
        emptyMessage={emptyMessage}
        onRetry={retry}>
        {children}
      </ScreenStateView>
    </View>
  );
}
