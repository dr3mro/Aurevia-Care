/**
 * A small, presentational push/pop stack used by the Account and Messages tabs.
 *
 * V1 ships no stack navigator dependency, and these two nested flows only need
 * a root menu plus pushed screens. Keeping the state machine here means the
 * screens stay presentational and every branch is reachable from a press test.
 * The native back gesture is out of scope; the visible Back control is the
 * single-pointer alternative required by the accessibility patterns.
 */
import { useState } from 'react';
import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import type { Locale } from './messages';

/** One route in the local stack. */
export interface StackRoute {
  /** Stable local key; use a business name, never a task or story identifier. */
  readonly key: string;
  /** Localized accessibility label for the menu control that pushes this route. */
  readonly label: string;
  /** Lazily build the screen so unpushed routes create no callbacks. */
  readonly render: () => ReactNode;
}

/** Props for {@link ScreenStack}. */
export interface ScreenStackProps {
  /** Test identifier for the stack container and its derived control IDs. */
  readonly testID: string;
  /** Localized heading announcement for the stack. */
  readonly title: string;
  /** Localized accessibility label of the Back control. */
  readonly backLabel: string;
  /** Active locale, applied as the text language of every label (UX-01). */
  readonly locale: Locale;
  /** Key of the route shown at the bottom of the stack. */
  readonly rootKey: string;
  /** All routes, with the root route first. */
  readonly routes: readonly StackRoute[];
}

/**
 * Render one route at a time with a root menu and a Back control when pushed.
 *
 * @param props stack identity, localized labels and route definitions
 * @returns the active screen with its push or back control
 */
export function ScreenStack({
  testID,
  title,
  backLabel,
  locale,
  rootKey,
  routes,
}: ScreenStackProps): React.JSX.Element {
  const [stack, setStack] = useState<readonly string[]>([rootKey]);
  const current = stack[stack.length - 1]!;
  const atRoot = current === rootKey;
  const active = routes.find(route => route.key === current)!;

  return (
    <View testID={testID} style={styles.container}>
      <Text accessibilityRole="header" accessibilityLanguage={locale} style={styles.heading}>
        {title}
      </Text>
      {atRoot ? (
        <View>
          {routes
            .filter(route => route.key !== rootKey)
            .map(route => (
              <Pressable
                key={route.key}
                testID={`${testID}-open-${route.key}`}
                accessibilityRole="button"
                accessibilityLabel={route.label}
                accessibilityLanguage={locale}
                style={styles.control}
                onPress={() => setStack(previous => [...previous, route.key])}>
                <Text accessibilityLanguage={locale}>{route.label}</Text>
              </Pressable>
            ))}
        </View>
      ) : (
        <Pressable
          testID={`${testID}-back`}
          accessibilityRole="button"
          accessibilityLabel={backLabel}
          accessibilityLanguage={locale}
          style={styles.control}
          onPress={() => setStack(previous => previous.slice(0, -1))}>
          <Text accessibilityLanguage={locale}>{backLabel}</Text>
        </Pressable>
      )}
      {active.render()}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16 },
  heading: { fontSize: 20, fontWeight: '600' },
  control: { minHeight: 44, justifyContent: 'center' },
});
