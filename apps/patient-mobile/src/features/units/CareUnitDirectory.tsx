import { Pressable, Text, useWindowDimensions, View } from 'react-native';
import type { ViewStyle } from 'react-native';
import * as SafeAreaContext from 'react-native-safe-area-context';

import { windowClassFor } from '../../app/layout/useWindowClass';
import type { CareUnit } from './types';

/** Upper bound for the centred reading column on large windows (dp). */
const MAX_READING_WIDTH = 720;
/** Platform touch-target floor for interactive controls (pt/dp). */
const MIN_TOUCH_TARGET = 44;
/** Inline/block gutter added on top of the safe-area insets (dp). */
const GUTTER = 12;

/** Safe-area insets at the four window edges (dp). */
interface EdgeInsets {
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
  readonly left: number;
}

/**
 * Resolve the safe-area hook from the module namespace rather than a named
 * import. The real package and this feature's tests expose `useSafeAreaInsets`
 * as a named export, while the repository's headless Jest setup supplies the
 * library mock under its `default` export. Both shapes resolve to one hook;
 * the fallback is never evaluated when the named export is present.
 */
const useInsets: () => EdgeInsets = SafeAreaContext.useSafeAreaInsets
  ?? (SafeAreaContext as unknown as { default: { useSafeAreaInsets: () => EdgeInsets } }).default.useSafeAreaInsets;

/** Props for the patient care-unit directory (MSG-01). */
export interface CareUnitDirectoryProps {
  /** Units already filtered to those with a verified relationship. */
  readonly units: readonly CareUnit[];
  /** Mirrors row direction for Arabic and other RTL locales (UX-01). */
  readonly isRTL: boolean;
  /** Called only after the patient explicitly selects a unit. */
  readonly onConnect: (unit: CareUnit) => void;
}

/**
 * Show eligible care units with organization, facility, name and topics.
 *
 * The list is supplied already scoped by the verified relationship rules; this
 * component never infers eligibility from its own props or from demographics.
 * Layout follows the current window (decision 0005): large windows get a centred
 * bounded reading column, content wraps so 200% text reflows instead of clipping,
 * controls keep a 44pt touch floor and safe-area insets pad all four edges. The
 * roles, labels and reading order are the same at every class. This is
 * presentation only — it adds no persistent clinical cache (PAT-02).
 */
export function CareUnitDirectory({ units, isRTL, onConnect }: CareUnitDirectoryProps) {
  const { width } = useWindowDimensions();
  const isLarge = windowClassFor(width) === 'large';
  const insets = useInsets();

  const listStyle: ViewStyle = {
    flexDirection: isRTL ? 'row-reverse' : 'row',
    flexWrap: 'wrap',
    width: '100%',
    maxWidth: isLarge ? MAX_READING_WIDTH : undefined,
    alignSelf: isLarge ? 'center' : undefined,
    paddingTop: insets.top + GUTTER,
    paddingBottom: insets.bottom + GUTTER,
    paddingStart: (isRTL ? insets.right : insets.left) + GUTTER,
    paddingEnd: (isRTL ? insets.left : insets.right) + GUTTER,
    gap: GUTTER,
  };

  const buttonStyle: ViewStyle = {
    minHeight: MIN_TOUCH_TARGET,
    minWidth: MIN_TOUCH_TARGET,
    justifyContent: 'center',
  };

  return (
    <View testID="unit-directory" style={listStyle}>
      {units.length === 0 ? (
        <Text accessibilityRole="text">No eligible care units</Text>
      ) : (
        units.map((unit) => (
          <View key={unit.id} testID={`unit-${unit.id}`} accessibilityRole="text" style={{ minHeight: MIN_TOUCH_TARGET }}>
            <Text accessibilityRole="header">{unit.name}</Text>
            <Text>{unit.organizationId}</Text>
            <Text>{unit.facilityId}</Text>
            <Text>{unit.topics.join(', ')}</Text>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`Connect to ${unit.name}`}
              testID={`connect-${unit.id}`}
              style={buttonStyle}
              onPress={() => onConnect(unit)}
            >
              <Text>Connect</Text>
            </Pressable>
          </View>
        ))
      )}
    </View>
  );
}
