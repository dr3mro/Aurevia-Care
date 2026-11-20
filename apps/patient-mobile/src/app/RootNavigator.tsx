import { StyleSheet, Text, View } from 'react-native';

import { appText, type Locale } from './messages';

/** Props for the placeholder root navigator (READY-33). */
export interface RootNavigatorProps {
  /** Active display locale; English and Arabic are both supported (UX-01). */
  readonly locale: Locale;
}

/**
 * Minimum root navigator for the patient app runnable shell.
 *
 * This is a deliberate placeholder: it presents the bilingual application
 * title through the shared message bundle while there is no navigation state,
 * authentication or network access. READY-34 replaces it with the four-tab
 * navigator without changing `App.tsx` or the Expo entry point.
 *
 * @param props the active display locale
 * @returns the rendered placeholder navigator
 */
export function RootNavigator({ locale }: RootNavigatorProps) {
  return (
    <View style={styles.container}>
      <Text
        accessibilityRole="header"
        accessibilityLanguage={locale}
        testID="app-title"
        style={styles.title}>
        {appText(locale, 'appTitle')}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  title: { fontSize: 24 },
});
