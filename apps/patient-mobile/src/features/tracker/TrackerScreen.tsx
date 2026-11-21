/**
 * Patient-visible "Where is it?" tracker screen (PAT-02, XCH-03, UX-01).
 *
 * Presentational only: every item is rendered from the authenticated response,
 * the step line comes from the released-facts projection, and no clinical data
 * is cached by this component. Direction and plain words follow the locale.
 */
import { StyleSheet, Text, View } from 'react-native';

import { Card, ScreenFrame, resolveScreenLocale, SCREEN_TOKENS } from '../appointments/patientDesign';

import { trackerText } from './messages';
import { isTrackerRtl, rowFlexDirection, stepLineFor } from './presentation';
import type { TrackerItem, TrackerLocale } from './types';

/** Visible separator between plain-word steps in the step line. */
const STEP_SEPARATOR = '›';

/** Props for the "Where is it?" tracker screen. */
export interface TrackerScreenProps {
  /** Tracked items loaded through the authenticated client; no local cache is kept. */
  readonly items: readonly TrackerItem[];
  /** Active display locale; defaults to the device direction for embedders. */
  readonly locale?: TrackerLocale;
}

/**
 * Render one plain-word step line per tracked item, hiding every internal step
 * the server did not release to the patient (PAT-02, XCH-03).
 *
 * @param props tracked items and display locale
 * @returns the tracker screen
 */
export function TrackerScreen({ items, locale = resolveScreenLocale() }: TrackerScreenProps): React.JSX.Element {
  return (
    <ScreenFrame
      testID="tracker"
      rootLabel="tracker"
      locale={locale}
      title={trackerText(locale, 'title')}
      subtitle={trackerText(locale, 'subtitle')}
      direction={isTrackerRtl(locale) ? 'rtl' : 'ltr'}>
      {items.length === 0 ? (
        <Text accessibilityLabel="tracker-empty" accessibilityLanguage={locale} style={styles.empty}>
          {trackerText(locale, 'empty')}
        </Text>
      ) : null}
      {items.map((item) => {
        const stepLine = stepLineFor(item, locale);
        return (
          <Card key={item.itemId} testID={`tracker-item-${item.itemId}`}>
            <Text accessibilityLabel="tracker-item-title" accessibilityLanguage={locale} style={styles.itemTitle}>
              {trackerText(locale, `${item.kind}.title`)}
            </Text>
            <View
              testID={`tracker-steps-${item.itemId}`}
              accessibilityLabel="tracker-step-line"
              style={{ flexDirection: rowFlexDirection(locale) }}>
              {stepLine.map((entry, index) => (
                <View key={entry.key} style={styles.stepSlot}>
                  {index === 0 ? null : (
                    <Text accessibilityLanguage={locale} style={styles.separator}>
                      {STEP_SEPARATOR}
                    </Text>
                  )}
                  <Text
                    testID={`tracker-step-${item.itemId}-${entry.key}`}
                    accessibilityLabel={`tracker-step-${entry.key}`}
                    accessibilityLanguage={locale}
                    style={styles.step}>
                    {entry.label}
                  </Text>
                </View>
              ))}
            </View>
          </Card>
        );
      })}
    </ScreenFrame>
  );
}

const styles = StyleSheet.create({
  empty: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.body },
  itemTitle: {
    color: SCREEN_TOKENS.color.textPrimary,
    fontSize: SCREEN_TOKENS.font.headingSm,
    fontWeight: SCREEN_TOKENS.font.semibold,
  },
  stepSlot: { flexDirection: 'row', alignItems: 'center' },
  separator: {
    color: SCREEN_TOKENS.color.textSecondary,
    fontSize: SCREEN_TOKENS.font.label,
    marginEnd: SCREEN_TOKENS.space.one,
  },
  step: {
    color: SCREEN_TOKENS.color.textPrimary,
    fontSize: SCREEN_TOKENS.font.label,
    marginEnd: SCREEN_TOKENS.space.one,
  },
});
