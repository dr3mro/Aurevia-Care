import { Pressable, Text, View } from 'react-native';

import { flowLabel, genericNoticeText } from './presentation';
import type { FlowPresentation, NoticeKind, PatientFlow } from './types';

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
  return (
    <View
      testID="patient-flow-home"
      style={{ flexDirection: presentation.direction === 'rtl' ? 'row-reverse' : 'row' }}
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
      >
        <Text>{presentation.settingsLabel}</Text>
      </Pressable>
    </View>
  );
}
