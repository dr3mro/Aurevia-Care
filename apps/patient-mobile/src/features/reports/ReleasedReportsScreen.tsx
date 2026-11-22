import { createContext, useContext } from 'react';
import { StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import type { ViewStyle } from 'react-native';
import { SafeAreaInsetsContext } from 'react-native-safe-area-context';
import type { EdgeInsets } from 'react-native-safe-area-context';

import { windowClassFor } from '../../app/layout/useWindowClass';
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

/**
 * Maximum readable content width on a large window (decision 0005). Derived
 * from the closed space scale so the value stays tied to the design tokens.
 */
const MAX_CONTENT_WIDTH = SCREEN_TOKENS.space.eight * 30;

/**
 * Narrowest presentation pane. It also guarantees every control laid out in a
 * pane keeps a touch area of at least 44 by 44 points (UX-01).
 */
const MIN_PANE_WIDTH = 48;

/** Zero insets for hosts that do not mount a safe-area provider. */
const NO_INSETS: EdgeInsets = { top: 0, right: 0, bottom: 0, left: 0 };
const NO_INSETS_CONTEXT = createContext<EdgeInsets | null>(NO_INSETS);

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
 *
 * Responsive presentation (decision 0005, UX-01) follows the current window, not the device:
 * every window class gets a centred content column and safe-area padding; large windows
 * additionally cap that column's width and lay a report's summary and its result detail out as
 * two side-by-side panes. No state, cache or data access is added — the data still arrives from
 * the authenticated client.
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
  // `useWindowDimensions` re-renders on rotation, split-screen and Slide Over;
  // the shared classifier keeps the breakpoint rule in one place. The inset
  // context is read through a fallback so hosts without a provider still render.
  const { width, fontScale } = useWindowDimensions();
  const insets = useContext(SafeAreaInsetsContext ?? NO_INSETS_CONTEXT) ?? NO_INSETS;
  const windowClass = windowClassFor(width);
  const isLarge = windowClass === 'large';
  const isRtlLocale = locale === 'ar';

  const contentStyle: ViewStyle = {
    alignSelf: 'center',
    width: '100%',
    paddingTop: insets.top,
    paddingBottom: insets.bottom,
    paddingStart: isRtlLocale ? insets.right : insets.left,
    paddingEnd: isRtlLocale ? insets.left : insets.right,
    ...(isLarge ? { maxWidth: MAX_CONTENT_WIDTH } : {}),
  };

  // The gutter grows with the system font scale so 200% text keeps breathing room.
  const panesStyle: ViewStyle = {
    flexDirection: isLarge ? 'row' : 'column',
    flexWrap: 'wrap',
    columnGap: Math.round(SCREEN_TOKENS.space.four * fontScale),
  };

  // Columns share space only in the two-pane layout; a stacked pane keeps its natural height.
  const paneStyle: ViewStyle = isLarge
    ? { flex: 1, minWidth: MIN_PANE_WIDTH, minHeight: MIN_PANE_WIDTH }
    : { minWidth: MIN_PANE_WIDTH, minHeight: MIN_PANE_WIDTH };

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
      <View testID="reports-content" style={contentStyle}>
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
              <View testID={`report-panes-${report.reportId}`} style={panesStyle}>
                <View testID={`report-summary-${report.reportId}`} style={paneStyle}>
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
                      <View testID={`pills-${report.reportId}`} style={styles.pills}>
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
                </View>
                {report.result === undefined ? null : (
                  <View testID={`report-detail-${report.reportId}`} style={paneStyle}>
                    <Card testID={`result-${report.reportId}`}>
                      <Text accessibilityLabel="result-label" accessibilityLanguage={locale} style={styles.title}>
                        {report.result.label}
                      </Text>
                      <Text accessibilityLabel="result-value" accessibilityLanguage={locale} style={styles.resultValue}>
                        {`${report.result.value} ${screenText(locale, 'reference')} ${report.result.referenceRange}`}
                      </Text>
                    </Card>
                  </View>
                )}
              </View>
              <Text accessibilityLabel="language" accessibilityLanguage={locale} style={styles.caption}>
                {isRtl(report) ? 'RTL' : 'LTR'}
              </Text>
            </View>
          );
        })}
      </View>
    </ScreenFrame>
  );
}

const styles = StyleSheet.create({
  title: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.headingSm, fontWeight: SCREEN_TOKENS.font.semibold },
  pills: { flexDirection: 'row', flexWrap: 'wrap' },
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
