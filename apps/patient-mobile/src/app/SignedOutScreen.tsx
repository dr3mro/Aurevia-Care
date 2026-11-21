/**
 * Signed-out destination after session expiry (PAT-03, WEB-09).
 *
 * The screen is fully presentational and uses only application-level strings;
 * it keeps no credential, clinical payload or navigation history.
 */
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { appText, type Locale } from './messages';

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
 * @param props locale and sign-in callback
 * @returns the signed-out screen
 */
export function SignedOutScreen({ locale, onSignIn }: SignedOutScreenProps): React.JSX.Element {
  return (
    <View testID="signed-out-screen" accessibilityRole="alert" style={styles.container}>
      <Text
        testID="signed-out-title"
        accessibilityRole="header"
        accessibilityLanguage={locale}
        style={styles.title}>
        {appText(locale, 'signedOutTitle')}
      </Text>
      <Text testID="signed-out-body" accessibilityLanguage={locale}>
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
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, justifyContent: 'center' },
  title: { fontSize: 20, fontWeight: '600' },
  control: { minHeight: 44, justifyContent: 'center' },
});
