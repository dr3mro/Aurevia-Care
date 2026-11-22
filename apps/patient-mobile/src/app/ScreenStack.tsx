/**
 * A small, presentational push/pop stack used by the Account and Messages tabs.
 *
 * V1 ships no stack navigator dependency, and these two nested flows only need
 * a root menu plus pushed screens. Keeping the state machine here means the
 * screens stay presentational and every branch is reachable from a press test.
 * The native back gesture is out of scope; the visible Back control is the
 * single-pointer alternative required by the accessibility patterns.
 *
 * Layout is presentation only (PAT-01, PAT-02, UX-01): on large windows the
 * menu and the active screen share a centred two-pane list/detail view, while
 * narrower windows keep the original one-pane push/pop. The stack state, labels,
 * focus order and data flow are identical in both presentations, and no clinical
 * data is cached. Safe-area insets and the native RTL flag are the only new
 * inputs; text is never shrunk or truncated so 200% scaling reflows instead.
 */
import { useState } from 'react';
import type { ReactNode } from 'react';
import { I18nManager, Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import * as SafeAreaContext from 'react-native-safe-area-context';

import { windowClassFor, type WindowLayout } from './layout/useWindowClass';

/** Upper bound for a readable column of content on a wide window. */
const MAX_READABLE_WIDTH = 960;

/** Zero insets for a headless render with no safe-area provider. */
const NO_INSETS: WindowLayout['insets'] = { top: 0, right: 0, bottom: 0, left: 0 };

/**
 * Read native safe-area insets for the stack's padding.
 *
 * The hook is resolved at call time rather than imported as a named binding:
 * a headless harness that replaces this module with a default-only export then
 * renders with zero insets instead of crashing, while the real app always has
 * the named hook. It is still one unconditional call, so hook order is stable.
 *
 * @returns top/right/bottom/left safe-area insets in density-independent pixels
 */
function useScreenInsets(): WindowLayout['insets'] {
  const readInsets = SafeAreaContext.useSafeAreaInsets as (() => WindowLayout['insets']) | undefined;
  return (readInsets ?? (() => NO_INSETS))();
}

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
  rootKey,
  routes,
}: ScreenStackProps): React.JSX.Element {
  const [stack, setStack] = useState<readonly string[]>([rootKey]);
  const current = stack[stack.length - 1]!;
  const atRoot = current === rootKey;
  const active = routes.find(route => route.key === current)!;
  const { width } = useWindowDimensions();
  const windowClass = windowClassFor(width);
  const isLarge = windowClass === 'large';
  const insets = useScreenInsets();
  const isRTL = I18nManager.isRTL;

  // Built once and reused by both presentations so labels, roles and press
  // handlers stay identical; only their placement changes with the window.
  const menu = (
    <View testID={`${testID}-menu`}>
      {routes
        .filter(route => route.key !== rootKey)
        .map(route => (
          <Pressable
            key={route.key}
            testID={`${testID}-open-${route.key}`}
            accessibilityRole="button"
            accessibilityLabel={route.label}
            style={styles.control}
            onPress={() => setStack(previous => [...previous, route.key])}>
            <Text>{route.label}</Text>
          </Pressable>
        ))}
    </View>
  );

  const back = (
    <Pressable
      testID={`${testID}-back`}
      accessibilityRole="button"
      accessibilityLabel={backLabel}
      style={styles.control}
      onPress={() => setStack(previous => previous.slice(0, -1))}>
      <Text>{backLabel}</Text>
    </Pressable>
  );

  return (
    <View
      testID={testID}
      style={[
        styles.container,
        isLarge && styles.readable,
        {
          paddingTop: Math.max(insets.top, 16),
          paddingBottom: Math.max(insets.bottom, 16),
          // Safe-area insets are physical; map them to logical edges so the
          // notch padding follows the start/end edge in both directions. The
          // row itself needs no reversal: 'row' mirrors under an RTL layout.
          paddingStart: Math.max(isRTL ? insets.right : insets.left, 16),
          paddingEnd: Math.max(isRTL ? insets.left : insets.right, 16),
          direction: isRTL ? 'rtl' : 'ltr',
        },
      ]}>
      <Text accessibilityRole="header" style={styles.heading}>
        {title}
      </Text>
      {isLarge ? (
        <View testID={`${testID}-panes`} style={styles.panes}>
          <View testID={`${testID}-pane-list`} style={styles.pane}>
            {menu}
          </View>
          <View testID={`${testID}-pane-detail`} style={styles.pane}>
            {atRoot ? null : back}
            {active.render()}
          </View>
        </View>
      ) : (
        <View testID={`${testID}-single`} style={styles.single}>
          {atRoot ? menu : back}
          {active.render()}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  // The heading and the panes share one bounded, centred reading column.
  readable: { maxWidth: MAX_READABLE_WIDTH, alignSelf: 'center', width: '100%' },
  heading: { fontSize: 20, fontWeight: '600', marginBottom: 8 },
  // minWidth as well as minHeight keeps the accessible frame at the native
  // target floor; hitSlop is deliberately not used to fake the size.
  control: { minHeight: 44, minWidth: 44, justifyContent: 'center' },
  panes: { flexDirection: 'row', width: '100%' },
  // minWidth 0 lets a long clinical value wrap instead of pushing a pane wider
  // than the window at 200% text.
  pane: { flex: 1, minWidth: 0 },
  single: { flex: 1 },
});
