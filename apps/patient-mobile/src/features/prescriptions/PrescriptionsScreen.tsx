/**
 * Patient-visible list of released prescriptions in plain status words (RX-02,
 * PAT-02, UX-01).
 *
 * The screen is deliberately presentational: the projections are passed in by
 * the authenticated loader, so this component keeps no clinical cache of its
 * own. A status word, never a readiness claim without dispensing evidence, is
 * shown for every prescription.
 */
import { StyleSheet, Text, View } from 'react-native';
import type { ViewStyle } from 'react-native';

import { Card, resolveScreenLocale, ScreenFrame, SCREEN_TOKENS, StatusPill } from '../appointments/patientDesign';

import { PRESCRIPTION_COPY } from './messages';
import { isRtl, pillVariantFor, plainStatusFor, statusLabel } from './status';
import type { PrescriptionLocale, ReleasedPrescription } from './types';

/** Direction-aware row layout (UX-01): the row mirrors the prescription's language. */
const RTL_ROW_STYLE: ViewStyle = { direction: 'rtl' };
const LTR_ROW_STYLE: ViewStyle = { direction: 'ltr' };

/** Props for the released-prescription list screen. */
export interface PrescriptionsScreenProps {
  /** Prescriptions already loaded through the authenticated client; no local clinical cache is kept. */
  readonly prescriptions: readonly ReleasedPrescription[];
  /** Generic load error, or `null` when the last load succeeded. */
  readonly error: string | null;
  /** Active display locale; defaults to the device direction. */
  readonly locale?: PrescriptionLocale;
}

/**
 * Render each released prescription with its plain status word (PAT-02).
 *
 * @param props prescriptions, optional load error and display locale
 * @returns the screen element
 */
export function PrescriptionsScreen({
  prescriptions,
  error,
  locale = resolveScreenLocale(),
}: PrescriptionsScreenProps): React.JSX.Element {
  const copy = PRESCRIPTION_COPY[locale];
  return (
    <ScreenFrame
      testID="prescriptions"
      rootLabel="prescriptions"
      locale={locale}
      title={copy.title}
      subtitle={copy.subtitle}>
      {error !== null ? (
        <Text accessibilityLabel="prescription-error" accessibilityLanguage={locale}>
          {error}
        </Text>
      ) : null}
      {prescriptions.length === 0 && error === null ? (
        <Text accessibilityLabel="no-prescriptions" accessibilityLanguage={locale}>
          {copy.empty}
        </Text>
      ) : null}
      {prescriptions.map((prescription) => {
        const status = plainStatusFor(prescription);
        const rtl = isRtl(prescription.language);
        return (
          <View
            key={prescription.prescriptionId}
            accessibilityLabel={`prescription-${prescription.prescriptionId}`}
            style={rtl ? RTL_ROW_STYLE : LTR_ROW_STYLE}>
            <Card testID={`prescription-card-${prescription.prescriptionId}`}>
              <Text accessibilityLabel="prescription-medication" accessibilityLanguage={locale} style={styles.medication}>
                {prescription.medicationLabel}
              </Text>
              <Text accessibilityLabel="prescription-reference" accessibilityLanguage={locale}>
                {`${copy.reference} ${prescription.reference}`}
              </Text>
              <StatusPill
                testID={`status-${prescription.prescriptionId}`}
                variant={pillVariantFor(status)}
                label={statusLabel(locale, status)}
                locale={locale}
              />
            </Card>
            <Text accessibilityLabel="language" accessibilityLanguage={locale} style={styles.caption}>
              {rtl ? 'RTL' : 'LTR'}
            </Text>
          </View>
        );
      })}
    </ScreenFrame>
  );
}

const styles = StyleSheet.create({
  medication: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.headingSm, fontWeight: SCREEN_TOKENS.font.semibold },
  caption: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.caption },
});
