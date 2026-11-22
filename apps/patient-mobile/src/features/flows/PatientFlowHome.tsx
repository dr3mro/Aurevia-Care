import { Pressable, Text, View, useWindowDimensions } from 'react-native';
import type { ViewStyle } from 'react-native';
import * as SafeArea from 'react-native-safe-area-context';

import { windowClassFor } from '../../app/layout/useWindowClass';
import type { WindowLayout } from '../../app/layout/useWindowClass';
import { flowLabel, genericNoticeText } from './presentation';
import type { FlowPresentation, NoticeKind, PatientFlow } from './types';

/**
 * Maximum inline size of the flow content on a wide window (PAT-01, PAT-02,
 * UX-01). Beyond this, lines would be too long to read comfortably, so the
 * centred column keeps a bounded measure instead of stretching edge to edge.
 */
const MAX_READABLE_WIDTH = 720;

/**
 * Shared accessible touch area: the larger of the 44 pt / 48 dp platform pair.
 * No fixed height is used, so text can grow to 200% and the tile grows with it.
 */
const TOUCH_TARGET_STYLE: ViewStyle = {
  minHeight: 48,
  minWidth: 48,
  justifyContent: 'center',
  alignItems: 'center',
  flexShrink: 1,
};

type Insets = WindowLayout['insets'];
const ZERO_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 };

/**
 * Resolve the safe-area hook once at module load. Headless renderers and older
 * `react-native-safe-area-context` builds can omit the named hook; neutral
 * insets keep the screen renderable instead of throwing in those hosts.
 */
const useSafeAreaInsetsOrDefault: () => Insets =
  typeof SafeArea.useSafeAreaInsets === 'function' ? SafeArea.useSafeAreaInsets : () => ZERO_INSETS;

/**
 * Read the window's responsive values. The class comes from the shared
 * `windowClassFor` classifier, so rotation, split-screen and Slide Over all
 * follow the single width rule from decision 0005.
 */
function useFlowHomeLayout(): WindowLayout {
  const { width, height, fontScale } = useWindowDimensions();
  const insets = useSafeAreaInsetsOrDefault();
  return {
    windowClass: windowClassFor(width),
    width,
    height,
    isLandscape: width > height,
    fontScale,
    insets,
  };
}

/** Props for the accessible patient flow home (PAT-01–03, MSG-01–07, UX-01). */
export interface PatientFlowHomeProps {
  /** Server-authored presentation policy applied by the app. */
  readonly presentation: FlowPresentation;
  /** Flows the verified patient may open. */
  readonly flows: readonly PatientFlow[];
  /** Approved generic notice previewed without clinical detail. */
  readonly previewNotice: NoticeKind;
  /** Called with the flow the patient explicitly selected. */
  readonly onSelectFlow: (flow: PatientFlow) => void;
  /** Opens platform-appropriate account settings for re-authentication. */
  readonly onOpenSettings: () => void;
}

/**
 * Render the patient's flow tiles with accessible labels, RTL-correct layout,
 * platform-specific secure-storage wording and a clinical-detail-free notice.
 *
 * Direction and content come entirely from the server-authored policy; this
 * component never infers locale, authorization or clinical content itself.
 */
export function PatientFlowHome({
  presentation,
  flows,
  previewNotice,
  onSelectFlow,
  onOpenSettings,
}: PatientFlowHomeProps) {
  const { windowClass, insets } = useFlowHomeLayout();
  // The window class is the only layout input: orientation and split-screen
  // change the width, not the device, so a rotation keeps the same rule
  // (decision 0005). This is presentation only — no clinical data is cached.
  const isLarge = windowClass === 'large';
  const isRtl = presentation.direction === 'rtl';

  return (
    <View
      testID="patient-flow-home"
      style={{
        flexDirection: isRtl ? 'row-reverse' : 'row',
        flexWrap: 'wrap',
        alignSelf: isLarge ? 'center' : 'stretch',
        maxWidth: isLarge ? MAX_READABLE_WIDTH : '100%',
        paddingTop: insets.top,
        paddingBottom: insets.bottom,
        // Logical insets: the start edge is the physical right edge in RTL.
        // Physical left/right padding mirrors differently on iOS and Android,
        // so logical insets are used deliberately (UX-01, decision 0005).
        paddingStart: isRtl ? insets.right : insets.left,
        paddingEnd: isRtl ? insets.left : insets.right,
      }}
    >
      <Text accessibilityRole="header">Aurevia Care</Text>
      {flows.length === 0 ? (
        <Text accessibilityRole="text">No flows available</Text>
      ) : (
        flows.map((flow) => (
          <Pressable
            key={flow}
            accessibilityRole="button"
            accessibilityLabel={flowLabel(flow, presentation.locale)}
            testID={`flow-${flow}`}
            onPress={() => onSelectFlow(flow)}
            style={TOUCH_TARGET_STYLE}
          >
            <Text>{flowLabel(flow, presentation.locale)}</Text>
          </Pressable>
        ))
      )}
      <Text accessibilityRole="text" testID="notice-preview">
        {presentation.noticeKinds.includes(previewNotice)
          ? genericNoticeText(previewNotice, presentation.locale)
          : ''}
      </Text>
      <Text accessibilityRole="text" testID="secure-storage">
        {presentation.secureStorageLabel}
      </Text>
      <Text accessibilityRole="text" testID="time-zone">
        {presentation.timeZoneContext}
      </Text>
      <Text accessibilityRole="text" testID="accessibility-profile">
        {presentation.accessibilityProfile}
      </Text>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={presentation.settingsLabel}
        testID="open-settings"
        onPress={onOpenSettings}
        style={TOUCH_TARGET_STYLE}
      >
        <Text>{presentation.settingsLabel}</Text>
      </Pressable>
    </View>
  );
}
