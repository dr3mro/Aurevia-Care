/**
 * Shared patient screen states (PAT-01–03, WEB-09).
 *
 * Every data-backed tab presents the same five states: loading, empty, error,
 * offline and session expired. Failure text is generic and carries only an
 * opaque correlation id; no transport body, token or patient field is rendered
 * or logged here.
 */
import { Pressable, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import type { ViewStyle } from 'react-native';
import type { ReactNode } from 'react';
import * as SafeAreaContext from 'react-native-safe-area-context';
import type { EdgeInsets } from 'react-native-safe-area-context';

import { TransportError } from '../api/transport';

import { windowClassFor } from './layout/useWindowClass';
import type { WindowClass, WindowLayout } from './layout/useWindowClass';
import { appText, type Locale } from './messages';

/** The responsive values a state placeholder needs from its window. */
interface StateLayout {
  readonly windowClass: WindowClass;
  readonly insets: WindowLayout['insets'];
}

/**
 * Resolve the safe-area hook once at module load. The named export is the
 * public API; a default-only shape also appears in some headless Jest setups,
 * where the state view must still render without a provider in the caller.
 */
const useSafeAreaInsets: () => EdgeInsets =
  typeof SafeAreaContext.useSafeAreaInsets === 'function'
    ? SafeAreaContext.useSafeAreaInsets
    : (
        SafeAreaContext as unknown as {
          default: { useSafeAreaInsets: () => EdgeInsets };
        }
      ).default.useSafeAreaInsets;

/**
 * Read the shared window classification and safe-area insets.
 *
 * The breakpoint rule is reused from the app's single layout boundary, so a
 * state placeholder follows rotation, split-screen and text scaling like the
 * rest of the app. Insets come from the existing safe-area library.
 *
 * @returns the current breakpoint class and safe-area insets
 */
function useStateLayout(): StateLayout {
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  return { windowClass: windowClassFor(width), insets };
}

/** Minimum touch target in density-independent points (44 pt iOS floor). */
const MIN_TOUCH_TARGET = 44;

/** Comfortable inner spacing in density-independent points. */
const BASE_PADDING = 16;

/** Maximum readable column width on large windows, in density-independent points. */
const MAX_READABLE_WIDTH = 720;

/**
 * Build the responsive container style for a state placeholder.
 *
 * Safe-area insets are added to the comfortable inner spacing so a message is
 * never hidden under a notch or home indicator in either orientation. The
 * larger horizontal inset is applied to both logical sides, so the same style
 * respects the safe area in right-to-left without a direction-specific branch.
 * Large windows cap the column width and centre it; compact and regular
 * windows fill the available width.
 *
 * @param windowClass breakpoint class of the current window
 * @param insets safe-area insets of the current window
 * @returns the container style for one state view
 */
function stateContainerStyle(
  windowClass: WindowClass,
  insets: WindowLayout['insets'],
): ViewStyle {
  const horizontalPadding = BASE_PADDING + Math.max(insets.left, insets.right);
  const safeArea: ViewStyle = {
    paddingTop: BASE_PADDING + insets.top,
    paddingBottom: BASE_PADDING + insets.bottom,
    paddingStart: horizontalPadding,
    paddingEnd: horizontalPadding,
    minHeight: MIN_TOUCH_TARGET,
  };
  if (windowClass === 'large') {
    return { ...safeArea, width: '100%', maxWidth: MAX_READABLE_WIDTH, alignSelf: 'center' };
  }
  return safeArea;
}

/** All labels a state view can show, already localized. */
export interface ScreenMessages {
  /** Shown while a request is in flight. */
  readonly loading: string;
  /** Generic failure text, never a technical detail. */
  readonly error: string;
  /** Connectivity failure text. */
  readonly offline: string;
  /** Session-expiry explanation. */
  readonly sessionExpired: string;
  /** Retry control label. */
  readonly retry: string;
  /** Sign-in control label. */
  readonly signIn: string;
}

/** Build the state labels for one locale. */
export function screenMessages(locale: Locale): ScreenMessages {
  return {
    loading: appText(locale, 'loading'),
    error: appText(locale, 'loadError'),
    offline: appText(locale, 'offline'),
    sessionExpired: appText(locale, 'sessionExpired'),
    retry: appText(locale, 'retry'),
    signIn: appText(locale, 'signIn'),
  };
}

/** The state of one loaded resource. A union makes an unhandled state a type error. */
export type ResourceState<T> =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly data: T }
  | { readonly kind: 'empty' }
  | { readonly kind: 'error'; readonly error: TransportError }
  | { readonly kind: 'offline'; readonly error: TransportError }
  | { readonly kind: 'session-expired'; readonly error: TransportError };

/**
 * Map a typed transport failure to a patient-facing state.
 *
 * A missing credential or a 401 is session expiry; connectivity and deadline
 * failures are offline; every other failure is a generic error. A transport
 * acknowledgement can never be mistaken for a successful load.
 *
 * @param error the typed failure raised by the transport
 * @returns the state that presents this failure
 */
export function screenStateForError(error: TransportError): ResourceState<never> {
  switch (error.kind) {
    case 'unauthenticated':
      return { kind: 'session-expired', error };
    case 'network':
    case 'timeout':
      return { kind: 'offline', error };
    default:
      return { kind: 'error', error };
  }
}

/** Props for {@link ScreenStateView}. */
export interface ScreenStateViewProps<T> {
  /** Current resource state. */
  readonly state: ResourceState<T>;
  /** Localized state labels. */
  readonly messages: ScreenMessages;
  /** Screen-specific empty explanation. */
  readonly emptyMessage: string;
  /** Called when the patient asks to retry; hidden when absent. */
  readonly onRetry?: () => void;
  /** Build the loaded screen from validated data. */
  readonly children: (data: T) => ReactNode;
}

/**
 * Render exactly one state of a data-backed screen.
 *
 * @param props resource state, labels, retry callback and ready-state renderer
 * @returns the state view or the loaded screen
 */
export function ScreenStateView<T>({
  state,
  messages,
  emptyMessage,
  onRetry,
  children,
}: ScreenStateViewProps<T>): React.JSX.Element {
  const { windowClass, insets } = useStateLayout();
  const containerStyle = stateContainerStyle(windowClass, insets);

  switch (state.kind) {
    case 'loading':
      return (
        <View
          testID="screen-loading"
          accessibilityRole="text"
          accessibilityLiveRegion="polite"
          style={containerStyle}>
          <Text>{messages.loading}</Text>
        </View>
      );
    case 'empty':
      return (
        <View testID="screen-empty" accessibilityRole="text" style={containerStyle}>
          <Text>{emptyMessage}</Text>
        </View>
      );
    case 'error':
      return (
        <View testID="screen-error" accessibilityRole="alert" style={containerStyle}>
          <Text>{messages.error}</Text>
          {state.error.correlationId ? (
            <Text testID="screen-correlation">{`Reference: ${state.error.correlationId}`}</Text>
          ) : null}
          {onRetry ? (
            <Pressable
              testID="screen-retry"
              accessibilityRole="button"
              accessibilityLabel={messages.retry}
              onPress={onRetry}
              style={styles.control}>
              <Text>{messages.retry}</Text>
            </Pressable>
          ) : null}
        </View>
      );
    case 'offline':
      return (
        <View testID="screen-offline" accessibilityRole="alert" style={containerStyle}>
          <Text>{messages.offline}</Text>
          {onRetry ? (
            <Pressable
              testID="screen-retry"
              accessibilityRole="button"
              accessibilityLabel={messages.retry}
              onPress={onRetry}
              style={styles.control}>
              <Text>{messages.retry}</Text>
            </Pressable>
          ) : null}
        </View>
      );
    case 'session-expired':
      return (
        <View testID="screen-session-expired" accessibilityRole="alert" style={containerStyle}>
          <Text>{messages.sessionExpired}</Text>
        </View>
      );
    case 'ready':
      return <>{children(state.data)}</>;
  }
}

const styles = StyleSheet.create({
  control: { minHeight: MIN_TOUCH_TARGET, minWidth: MIN_TOUCH_TARGET, justifyContent: 'center' },
});
