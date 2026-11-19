import { Pressable, Text, View } from 'react-native';

import type { CareUnit } from './types';

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
 */
export function CareUnitDirectory({ units, isRTL, onConnect }: CareUnitDirectoryProps) {
  return (
    <View testID="unit-directory" style={{ flexDirection: isRTL ? 'row-reverse' : 'row' }}>
      {units.length === 0 ? (
        <Text accessibilityRole="text">No eligible care units</Text>
      ) : (
        units.map((unit) => (
          <View key={unit.id} testID={`unit-${unit.id}`} accessibilityRole="text">
            <Text accessibilityRole="header">{unit.name}</Text>
            <Text>{unit.organizationId}</Text>
            <Text>{unit.facilityId}</Text>
            <Text>{unit.topics.join(', ')}</Text>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`Connect to ${unit.name}`}
              testID={`connect-${unit.id}`}
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
