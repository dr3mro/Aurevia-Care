import { StyleSheet, Text, View } from 'react-native';
import type { ViewStyle } from 'react-native';

import {
  Card,
  PrimaryButton,
  resolveScreenLocale,
  screenText,
  ScreenFrame,
  SCREEN_TOKENS,
  StatusPill,
} from '../appointments/patientDesign';
import type { Locale, TabId } from '../appointments/patientDesign';

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
  /** Active display locale; defaults to English for embedders without a locale prop. */
  readonly locale?: Locale;
  /** Render the standalone phone-frame tab bar for a design review. */
  readonly showTabBar?: boolean;
  /** Highlighted standalone tab. */
  readonly activeTab?: TabId;
  /** Standalone tab handler. */
  readonly onTab?: (tab: TabId) => void;
}

/**
 * Patient-visible list of released reports with revision and correction context (PAT-02).
 *
 * The component is deliberately presentational: loading, confirmation and server authorization
 * live in the client and download flow so screen behavior stays traceable to a test. A withdrawn
 * release renders a dashed notice and no download control, because server authorization would
 * refuse it anyway (XCH-06).
 */
export function ReleasedReportsScreen({
  reports,
  error,
  onDownload,
  locale = resolveScreenLocale(),
  showTabBar = false,
  activeTab = 'reports',
  onTab,
}: ReleasedReportsScreenProps) {
  return (
    <ScreenFrame
      testID="released-reports"
      rootLabel="released-reports"
      locale={locale}
      title={screenText(locale, 'reportsTitle')}
      subtitle={screenText(locale, 'reportsSubtitle')}
      showTabBar={showTabBar}
      activeTab={activeTab}
      onTab={onTab}>
      {error !== null ? <Text accessibilityLabel="report-error" accessibilityLanguage={locale}>{error}</Text> : null}
      {reports.length === 0 && error === null ? (
        <Text accessibilityLabel="no-reports" accessibilityLanguage={locale}>
          {screenText(locale, 'noReports')}
        </Text>
      ) : null}
      {reports.map((report) => {
        const correction = correctionLabel(report);
        const withdrawn = report.releaseState === 'withdrawn';
        return (
          <View
            key={`${report.reportId}:${report.revision}`}
            accessibilityLabel={`report-${report.reportId}`}
            style={isRtl(report) ? RTL_ROW_STYLE : LTR_ROW_STYLE}>
            {withdrawn ? (
              <Card testID={`withdrawn-${report.reportId}`} dashed>
                <Text accessibilityLabel="withdrawn-title" accessibilityLanguage={locale} style={styles.withdrawnTitle}>
                  {screenText(locale, 'withdrawnTitle')}
                </Text>
                <Text accessibilityLabel="withdrawn-body" accessibilityLanguage={locale}>
                  {screenText(locale, 'withdrawnBody')}
                </Text>
              </Card>
            ) : (
              <Card testID={`report-card-${report.reportId}`}>
                {report.title === undefined ? null : (
                  <Text accessibilityLabel="report-title" accessibilityLanguage={locale} style={styles.title}>
                    {report.title}
                  </Text>
                )}
                <Text accessibilityLabel="issuer" accessibilityLanguage={locale}>{report.issuingOrganization}</Text>
                <Text accessibilityLabel="effective-date" accessibilityLanguage={locale}>
                  {report.effectiveDate}
                </Text>
                <View style={styles.pills}>
                  <Text
                    testID={`revision-${report.reportId}`}
                    accessibilityLabel="revision"
                    accessibilityLanguage={locale}
                    style={styles.pillText}>
                    {`${screenText(locale, 'revision')} ${report.revision}`}
                  </Text>
                  {correction === null ? null : (
                    <StatusPill
                      testID={`corrected-${report.reportId}`}
                      variant="warning"
                      label={screenText(locale, 'corrected')}
                      locale={locale}
                    />
                  )}
                </View>
                {correction === null ? null : (
                  <View testID={`correction-${report.reportId}`} accessibilityLabel="correction" style={styles.correction}>
                    <Text accessibilityLanguage={locale}>{correction}</Text>
                  </View>
                )}
                <PrimaryButton
                  testID={`download-${report.reportId}`}
                  label={screenText(locale, 'downloadPdf')}
                  locale={locale}
                  onPress={() => onDownload(report)}
                />
                <Text accessibilityLabel="download-check" accessibilityLanguage={locale} style={styles.downloadCheck}>
                  {screenText(locale, 'downloadCheck')}
                </Text>
              </Card>
            )}
            {report.result === undefined ? null : (
              <Card testID={`result-${report.reportId}`}>
                <Text accessibilityLabel="result-label" accessibilityLanguage={locale} style={styles.title}>
                  {report.result.label}
                </Text>
                <Text accessibilityLabel="result-value" accessibilityLanguage={locale} style={styles.resultValue}>
                  {`${report.result.value} ${screenText(locale, 'reference')} ${report.result.referenceRange}`}
                </Text>
              </Card>
            )}
            <Text accessibilityLabel="language" accessibilityLanguage={locale} style={styles.caption}>
              {isRtl(report) ? 'RTL' : 'LTR'}
            </Text>
          </View>
        );
      })}
    </ScreenFrame>
  );
}

const styles = StyleSheet.create({
  title: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.headingSm, fontWeight: SCREEN_TOKENS.font.semibold },
  pills: { flexDirection: 'row' },
  pillText: {
    backgroundColor: SCREEN_TOKENS.color.surfaceSunken,
    borderRadius: SCREEN_TOKENS.radius.pill,
    color: SCREEN_TOKENS.color.textSecondary,
    fontSize: SCREEN_TOKENS.font.label,
    paddingHorizontal: SCREEN_TOKENS.space.two,
    paddingVertical: SCREEN_TOKENS.space.one,
  },
  correction: {
    backgroundColor: SCREEN_TOKENS.color.warningSurface,
    borderColor: SCREEN_TOKENS.color.warningBorder,
    borderRadius: SCREEN_TOKENS.radius.md,
    borderWidth: SCREEN_TOKENS.border.thin,
    padding: SCREEN_TOKENS.space.three,
  },
  withdrawnTitle: { color: SCREEN_TOKENS.color.textPrimary, fontWeight: SCREEN_TOKENS.font.semibold },
  resultValue: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.numericLg },
  caption: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.caption },
  downloadCheck: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.label },
});
