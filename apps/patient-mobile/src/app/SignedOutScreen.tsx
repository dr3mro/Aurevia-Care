/**
 * Signed-out destination after session expiry (PAT-01, PAT-02, UX-01, WEB-09).
 *
 * The screen is fully presentational and uses only application-level strings;
 * it keeps no credential, clinical payload or navigation history. Responsive
 * layout is presentation only (decision 0005): the current window decides the
 * content width and safe-area padding, with no persistent clinical cache and
 * no change to the accessibility contract or the sign-in action.
 */
import { Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import * as SafeArea from 'react-native-safe-area-context';

import { windowClassFor } from './layout/useWindowClass';
import { appText, type Locale } from './messages';

/** Maximum readable content width on a large window. */
const MAX_CONTENT_WIDTH = 640;
/** Baseline padding added to each safe-area inset. */
const BASE_PADDING = 16;
/** Minimum accessible touch-target size in density-independent points. */
const MIN_TOUCH_TARGET = 44;

interface Insets {
  top: number;
  right: number;
  bottom: number;
  left: number;
}
const ZERO_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 };

/**
 * Resolve the safe-area hook for the current runtime. Production always exposes
 * the named hook; the repository's shared headless Jest mock exports only a
 * default object, so fall back to zero insets there rather than throwing. The
 * fallback keeps headless rendering possible without adding a dependency or a
 * persistent store, and never changes the visible layout in the real app.
 */
const useSafeInsets: () => Insets = SafeArea.useSafeAreaInsets ?? ((): Insets => ZERO_INSETS);

/** Props for {@link SignedOutScreen}. */
export interface SignedOutScreenProps {
  /** Active display locale. */
  readonly locale: Locale;
  /** Returns to the signed-in shell in this synthetic build. */
  readonly onSignIn: () => void;
}

/**
 * Show that the session ended and offer a way back to the shell.
 *
 * On a large window the message column is capped and centred for comfortable
 * reading; on compact and regular windows it fills the available width. Safe-
 * area insets become padding on every edge, so nothing is clipped in landscape
 * or split-screen. Text keeps the platform default scaling (up to 200%) with
 * no fixed line count, and the sign-in control keeps a 44 pt floor. The content
 * wrapper is not a screen-reader element, so focus order and labels of the
 * header, body and button are unchanged; its writing direction mirrors Arabic.
 *
 * @param props locale and sign-in callback
 * @returns the signed-out screen
 */
export function SignedOutScreen({ locale, onSignIn }: SignedOutScreenProps): React.JSX.Element {
  const { width } = useWindowDimensions();
  const insets = useSafeInsets();
  const isLarge = windowClassFor(width) === 'large';

  return (
    <View
      testID="signed-out-screen"
      accessibilityRole="alert"
      style={[
        styles.container,
        {
          paddingTop: insets.top + BASE_PADDING,
          paddingBottom: insets.bottom + BASE_PADDING,
          // Safe-area insets are device-physical (notch, home indicator), so
          // they map to left/right rather than logical start/end. Swapping them
          // for logical props would mirror the wrong inset in Arabic RTL.
          paddingLeft: insets.left + BASE_PADDING,
          paddingRight: insets.right + BASE_PADDING,
        },
      ]}>
      <View
        testID="signed-out-content"
        style={[
          styles.content,
          { direction: locale === 'ar' ? 'rtl' : 'ltr' },
          isLarge ? styles.largeContent : null,
        ]}>
        <Text
          testID="signed-out-title"
          accessibilityRole="header"
          accessibilityLanguage={locale}
          style={styles.title}>
          {appText(locale, 'signedOutTitle')}
        </Text>
        <Text testID="signed-out-body" accessibilityLanguage={locale} style={styles.body}>
          {appText(locale, 'signedOutBody')}
        </Text>
        <Pressable
          testID="sign-in-action"
          accessibilityRole="button"
          accessibilityLabel={appText(locale, 'signIn')}
          accessibilityLanguage={locale}
          onPress={onSignIn}
          style={styles.control}>
          <Text accessibilityLanguage={locale}>{appText(locale, 'signIn')}</Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center' },
  content: { width: '100%' },
  largeContent: { maxWidth: MAX_CONTENT_WIDTH, alignSelf: 'center' },
  title: { fontSize: 20, fontWeight: '600' },
  body: { marginTop: 8 },
  control: { minHeight: MIN_TOUCH_TARGET, minWidth: MIN_TOUCH_TARGET, justifyContent: 'center' },
});
