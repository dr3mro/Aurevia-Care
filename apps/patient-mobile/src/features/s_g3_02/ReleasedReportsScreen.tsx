import { Pressable, Text, View } from 'react-native';
import type { ViewStyle } from 'react-native';

import { correctionLabel, isRtl } from './presentation';
import type { ReleasedReport } from './types';

/** Direction-aware row layout (UX-01): metadata follows the report's language direction. */
const RTL_ROW_STYLE: ViewStyle = { direction: 'rtl' };
const LTR_ROW_STYLE: ViewStyle = { direction: 'ltr' };

/** Props for the released report list screen (PAT-02). */
export interface ReleasedReportsScreenProps {
  /** Reports already loaded through the authenticated client; no local clinical cache is kept. */
  readonly reports: readonly ReleasedReport[];
  /** Generic load error, or `null` when the last load succeeded. */
  readonly error: string | null;
  /** Called with the exact revision the patient chose to save. */
  readonly onDownload: (report: ReleasedReport) => void;
}

/**
 * Patient-visible list of released reports with revision and correction context (PAT-02).
 *
 * The component is deliberately presentational: loading, confirmation and server authorization
 * live in the client and download flow so screen behavior stays traceable to a test.
 */
export function ReleasedReportsScreen({ reports, error, onDownload }: ReleasedReportsScreenProps) {
  return (
    <View accessibilityLabel="released-reports">
      {error !== null ? <Text accessibilityLabel="report-error">{error}</Text> : null}
      {reports.length === 0 && error === null ? (
        <Text accessibilityLabel="no-reports">No released reports</Text>
      ) : null}
      {reports.map((report) => {
        const correction = correctionLabel(report);
        return (
          <View
            key={`${report.reportId}:${report.revision}`}
            accessibilityLabel={`report-${report.reportId}`}
            style={isRtl(report) ? RTL_ROW_STYLE : LTR_ROW_STYLE}
          >
            <Text accessibilityLabel="revision">{`Revision ${report.revision}`}</Text>
            <Text accessibilityLabel="issuer">{report.issuingOrganization}</Text>
            <Text accessibilityLabel="effective-date">{report.effectiveDate}</Text>
            {correction !== null ? (
              <Text accessibilityLabel="correction">{correction}</Text>
            ) : null}
            <Text accessibilityLabel="language">{isRtl(report) ? 'RTL' : 'LTR'}</Text>
            <Pressable accessibilityLabel={`download-${report.reportId}`} onPress={() => onDownload(report)}>
              <Text>Download</Text>
            </Pressable>
          </View>
        );
      })}
    </View>
  );
}
