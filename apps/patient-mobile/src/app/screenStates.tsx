/**
 * Shared patient screen states (PAT-01–03, WEB-09).
 *
 * Every data-backed tab presents the same five states: loading, empty, error,
 * offline and session expired. Failure text is generic and carries only an
 * opaque correlation id; no transport body, token or patient field is rendered
 * or logged here.
 */
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import type { ReactNode } from 'react';

import { TransportError } from '../api/transport';

import { appText, type Locale } from './messages';

/** All labels a state view can show, already localized. */
export interface ScreenMessages {
  /** Active display locale; applied as `accessibilityLanguage` on every text run. */
  readonly locale: Locale;
  /** Label that introduces an opaque correlation identifier. */
  readonly reference: string;
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
    locale,
    loading: appText(locale, 'loading'),
    error: appText(locale, 'loadError'),
    offline: appText(locale, 'offline'),
    sessionExpired: appText(locale, 'sessionExpired'),
    reference: appText(locale, 'referencePrefix'),
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
  switch (state.kind) {
    case 'loading':
      return (
        <View
          testID="screen-loading"
          accessibilityRole="text"
          accessibilityLiveRegion="polite"
          style={styles.state}>
          <Text accessibilityLanguage={messages.locale}>{messages.loading}</Text>
        </View>
      );
    case 'empty':
      return (
        <View testID="screen-empty" accessibilityRole="text" style={styles.state}>
          <Text accessibilityLanguage={messages.locale}>{emptyMessage}</Text>
        </View>
      );
    case 'error':
      return (
        <View testID="screen-error" accessibilityRole="alert" style={styles.state}>
          <Text accessibilityLanguage={messages.locale}>{messages.error}</Text>
          {state.error.correlationId ? (
            <View testID="screen-correlation" style={styles.reference}>
              <Text accessibilityLanguage={messages.locale}>{messages.reference}</Text>
              {/* An identifier is a Latin run in both directions; keep it in its
                  own node so a screen reader does not read it in Arabic. */}
              <Text
                testID="screen-correlation-id"
                accessibilityLanguage="en"
                selectable
                style={styles.identifier}>
                {state.error.correlationId}
              </Text>
            </View>
          ) : null}
          {onRetry ? (
            <Pressable
              testID="screen-retry"
              accessibilityRole="button"
              accessibilityLabel={messages.retry}
              accessibilityLanguage={messages.locale}
              onPress={onRetry}
              style={styles.control}>
              <Text accessibilityLanguage={messages.locale}>{messages.retry}</Text>
            </Pressable>
          ) : null}
        </View>
      );
    case 'offline':
      return (
        <View testID="screen-offline" accessibilityRole="alert" style={styles.state}>
          <Text accessibilityLanguage={messages.locale}>{messages.offline}</Text>
          {onRetry ? (
            <Pressable
              testID="screen-retry"
              accessibilityRole="button"
              accessibilityLabel={messages.retry}
              accessibilityLanguage={messages.locale}
              onPress={onRetry}
              style={styles.control}>
              <Text accessibilityLanguage={messages.locale}>{messages.retry}</Text>
            </Pressable>
          ) : null}
        </View>
      );
    case 'session-expired':
      return (
        <View testID="screen-session-expired" accessibilityRole="alert" style={styles.state}>
          <Text accessibilityLanguage={messages.locale}>{messages.sessionExpired}</Text>
        </View>
      );
    case 'ready':
      return <>{children(state.data)}</>;
  }
}

const styles = StyleSheet.create({
  state: { padding: 16, minHeight: 44 },
  control: { minHeight: 44, justifyContent: 'center' },
  // `row` mirrors under RTL so the localized prefix stays at the reading start
  // and the Latin identifier follows it in both directions. The identifier is
  // never wrapped: a broken hyphen could change what the patient reads back.
  reference: { flexDirection: 'row' },
  // Native mono projection from the token contract; `default` keeps the type
  // total so no branch is introduced here.
  identifier: {
    fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace', default: 'monospace' }),
  },
});
