/**
 * Patient-visible list of handouts the clinician released (PAT-02, CLN-07).
 *
 * The component is deliberately presentational: loading, validation, release
 * filtering and server authorization live in the client and download flow, so
 * screen behavior stays traceable to a test. A handout whose newer version
 * replaced it shows a Corrected pill and its correction context (PAT-02).
 */
import { StyleSheet, Text, View } from 'react-native';
import type { ViewStyle } from 'react-native';

import {
  Card,
  PrimaryButton,
  resolveScreenLocale,
  ScreenFrame,
  SCREEN_TOKENS,
  StatusPill,
} from '../appointments/patientDesign';
import type { Locale, TabId } from '../appointments/patientDesign';

import { handoutText } from './messages';
import { correctedNote, isRtl, kindText } from './presentation';
import type { ReleasedHandout } from './types';

/** Direction-aware row layout (UX-01): metadata follows the handout's language. */
const RTL_ROW_STYLE: ViewStyle = { direction: 'rtl' };
const LTR_ROW_STYLE: ViewStyle = { direction: 'ltr' };

/** Props for the released handout list screen. */
export interface HandoutsScreenProps {
  /** Handouts already loaded through the authenticated client; no local clinical cache is kept. */
  readonly handouts: readonly ReleasedHandout[];
  /** Generic load error, or `null` when the last load succeeded. */
  readonly error: string | null;
  /** Called with the exact handout the patient chose to view. */
  readonly onView: (handout: ReleasedHandout) => void;
  /** Called with the exact handout the patient chose to save. */
  readonly onSave: (handout: ReleasedHandout) => void;
  /** Called with the exact handout the patient chose to share. */
  readonly onShare: (handout: ReleasedHandout) => void;
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
 * Render the released handout inbox with view, save and share actions.
 *
 * @param props handouts, error state, intent handlers and optional display settings
 */
export function HandoutsScreen({
  handouts,
  error,
  onView,
  onSave,
  onShare,
  locale = resolveScreenLocale(),
  showTabBar = false,
  activeTab = 'reports',
  onTab,
}: HandoutsScreenProps) {
  return (
    <ScreenFrame
      testID="handouts"
      rootLabel="handouts"
      locale={locale}
      title={handoutText(locale, 'handoutsTitle')}
      subtitle={handoutText(locale, 'handoutsSubtitle')}
      showTabBar={showTabBar}
      activeTab={activeTab}
      onTab={onTab}>
      {error !== null ? (
        <Text accessibilityLabel="handout-error" accessibilityLanguage={locale}>{error}</Text>
      ) : null}
      {handouts.length === 0 && error === null ? (
        <Text accessibilityLabel="no-handouts" accessibilityLanguage={locale}>
          {handoutText(locale, 'noHandouts')}
        </Text>
      ) : null}
      {handouts.map((handout) => {
        const correction = correctedNote(locale, handout);
        return (
          <View
            key={`${handout.handoutId}:${handout.version}`}
            accessibilityLabel={`handout-${handout.handoutId}`}
            style={isRtl(handout) ? RTL_ROW_STYLE : LTR_ROW_STYLE}>
            <Card testID={`handout-card-${handout.handoutId}`}>
              <Text accessibilityLabel="handout-kind" accessibilityLanguage={locale} style={styles.title}>
                {kindText(locale, handout.kind)}
              </Text>
              <Text accessibilityLabel="issuer" accessibilityLanguage={locale}>{handout.issuingOrganization}</Text>
              <Text accessibilityLabel="released-date" accessibilityLanguage={locale}>
                {`${handoutText(locale, 'releasedOn')} ${handout.releasedDate}`}
              </Text>
              <View style={styles.pills}>
                <Text
                  testID={`version-${handout.handoutId}`}
                  accessibilityLabel="version"
                  accessibilityLanguage={locale}
                  style={styles.pillText}>
                  {`${handoutText(locale, 'version')} ${handout.version}`}
                </Text>
                {correction === null ? null : (
                  <StatusPill
                    testID={`corrected-${handout.handoutId}`}
                    variant="warning"
                    label={handoutText(locale, 'corrected')}
                    locale={locale}
                  />
                )}
              </View>
              {correction === null ? null : (
                <View testID={`correction-${handout.handoutId}`} accessibilityLabel="correction" style={styles.correction}>
                  <Text accessibilityLanguage={locale}>{correction}</Text>
                </View>
              )}
              <Text accessibilityLabel="download-check" accessibilityLanguage={locale} style={styles.downloadCheck}>
                {handoutText(locale, 'downloadCheck')}
              </Text>
              <PrimaryButton
                testID={`view-${handout.handoutId}`}
                label={handoutText(locale, 'view')}
                locale={locale}
                onPress={() => onView(handout)}
              />
              <PrimaryButton
                testID={`save-${handout.handoutId}`}
                label={handoutText(locale, 'saveCopy')}
                locale={locale}
                onPress={() => onSave(handout)}
              />
              <PrimaryButton
                testID={`share-${handout.handoutId}`}
                label={handoutText(locale, 'share')}
                locale={locale}
                onPress={() => onShare(handout)}
              />
            </Card>
            <Text accessibilityLabel="language" accessibilityLanguage={locale} style={styles.caption}>
              {isRtl(handout) ? 'RTL' : 'LTR'}
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
  downloadCheck: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.label, marginTop: SCREEN_TOKENS.space.two },
  caption: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.caption },
});
