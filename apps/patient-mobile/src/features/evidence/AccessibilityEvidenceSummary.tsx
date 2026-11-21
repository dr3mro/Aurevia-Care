import { Text, View, useWindowDimensions } from 'react-native';
import type { TextStyle, ViewStyle } from 'react-native';
import * as SafeAreaContext from 'react-native-safe-area-context';

import { windowClassFor } from '../../app/layout/useWindowClass';
import { evidenceFailureText, evidenceSummaryLabel } from './accessibility';
import type { AccessibilityEvidenceReport } from './types';

const RTL_ROW_STYLE: ViewStyle = { flexDirection: 'row-reverse' };
const LTR_ROW_STYLE: ViewStyle = { flexDirection: 'row' };

/** Comfortable reading measure; wide windows centre to this width (PAT-01, PAT-02, UX-01). */
const MAX_READABLE_WIDTH = 720;
/** 44 pt floor for every row and pointer target, on every window class (UX-01). */
const MIN_TARGET_SIZE = 44;

/** Wrap instead of clipping so text can scale to 200% on any window. */
const RESPONSIVE_CONTAINER_STYLE: ViewStyle = {
  flexWrap: 'wrap',
  alignItems: 'center',
  minHeight: MIN_TARGET_SIZE,
};

/** Wide windows cap the measure and centre it rather than stretching the text. */
const LARGE_CONTAINER_STYLE: ViewStyle = {
  alignSelf: 'center',
  maxWidth: MAX_READABLE_WIDTH,
  width: '100%',
};

/** Let long labels wrap, and keep a comfortable target floor in both axes. */
const CONTENT_STYLE: TextStyle = {
  flexShrink: 1,
  minHeight: MIN_TARGET_SIZE,
  minWidth: MIN_TARGET_SIZE,
};

type Insets = ReturnType<typeof SafeAreaContext.useSafeAreaInsets>;

const NO_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 };

/**
 * Bind once at module load. Some headless test doubles expose the hook only on
 * the package's default export; falling back to zero insets keeps the summary
 * renderable there while the app uses the real safe-area values.
 */
const useInsets: () => Insets =
  typeof SafeAreaContext.useSafeAreaInsets === 'function'
    ? SafeAreaContext.useSafeAreaInsets
    : () => NO_INSETS;

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
 * Layout is presentation only: it follows the current window (not the device),
 * respects safe-area insets, caps and centres the measure on large windows, and
 * leaves font scaling, screen-reader labels and focus order untouched (PAT-02).
 */
export function AccessibilityEvidenceSummary({
  report,
  isRTL,
}: AccessibilityEvidenceSummaryProps) {
  const { width } = useWindowDimensions();
  const windowClass = windowClassFor(width);
  const insets = useInsets();
  const statusLabel = evidenceSummaryLabel(report);
  const detailLabel = evidenceFailureText(report);
  // Physical insets are mapped to logical inline padding so RTL mirrors correctly.
  const paddingStart = isRTL ? insets.right : insets.left;
  const paddingEnd = isRTL ? insets.left : insets.right;
  const containerStyle: ViewStyle = {
    ...(isRTL ? RTL_ROW_STYLE : LTR_ROW_STYLE),
    ...RESPONSIVE_CONTAINER_STYLE,
    paddingTop: insets.top,
    paddingBottom: insets.bottom,
    paddingStart,
    paddingEnd,
    ...(windowClass === 'large' ? LARGE_CONTAINER_STYLE : {}),
  };
  return (
    <View testID="a11y-evidence-summary" style={containerStyle}>
      <Text testID="a11y-evidence-title" accessibilityRole="header" style={CONTENT_STYLE}>
        Accessibility evidence
      </Text>
      <Text
        testID="a11y-evidence-status"
        accessibilityRole="text"
        accessibilityLabel={statusLabel}
        style={CONTENT_STYLE}
      >
        {statusLabel}
      </Text>
      <Text
        testID="a11y-evidence-detail"
        accessibilityRole="text"
        accessibilityLabel={detailLabel}
        style={CONTENT_STYLE}
      >
        {detailLabel}
      </Text>
    </View>
  );
}
