import { Text, View } from 'react-native';
import type { ViewStyle } from 'react-native';

import { evidenceFailureText, evidenceSummaryLabel } from './accessibility';
import type { AccessibilityEvidenceReport } from './types';

const RTL_ROW_STYLE: ViewStyle = { flexDirection: 'row-reverse' };
const LTR_ROW_STYLE: ViewStyle = { flexDirection: 'row' };

/** Props for the accessible UX-01 evidence summary (UX-01, PAT-01–03, MSG-01–07). */
export interface AccessibilityEvidenceSummaryProps {
  /** Pre-evaluated evidence report; this component performs no evaluation. */
  readonly report: AccessibilityEvidenceReport;
  /** Mirrors the row direction for Arabic and other RTL locales. */
  readonly isRTL: boolean;
}

/**
 * Render a synthetic accessibility-evidence summary with native roles and labels.
 *
 * All pass/fail decisions are computed by the tested pure helpers; the JSX only
 * presents the supplied report and mirrors layout direction for RTL locales.
 */
export function AccessibilityEvidenceSummary({
  report,
  isRTL,
}: AccessibilityEvidenceSummaryProps) {
  const statusLabel = evidenceSummaryLabel(report);
  const detailLabel = evidenceFailureText(report);
  const rowStyle = isRTL ? RTL_ROW_STYLE : LTR_ROW_STYLE;
  return (
    <View testID="a11y-evidence-summary" style={rowStyle}>
      <Text testID="a11y-evidence-title" accessibilityRole="header">
        Accessibility evidence
      </Text>
      <Text
        testID="a11y-evidence-status"
        accessibilityRole="text"
        accessibilityLabel={statusLabel}
      >
        {statusLabel}
      </Text>
      <Text
        testID="a11y-evidence-detail"
        accessibilityRole="text"
        accessibilityLabel={detailLabel}
      >
        {detailLabel}
      </Text>
    </View>
  );
}
