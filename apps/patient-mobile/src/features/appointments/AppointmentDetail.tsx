/**
 * Presentational detail view for one selected appointment (PAT-01, UX-01).
 *
 * The row is already validated by the authenticated loader. This component
 * adds no request, no navigation dependency and no clinical decision: it shows
 * the fields of exactly the row that was pressed and offers a single Back
 * control so a patient can return to the list with a pointer or a screen reader.
 */
import { Pressable, StyleSheet, Text } from 'react-native';

import type { AppointmentView } from './appointments';
import { Card, screenText, SCREEN_TOKENS } from './patientDesign';
import type { Locale } from './patientDesign';

/** Props for {@link AppointmentDetail}. */
export interface AppointmentDetailProps {
  /** The exact row whose details control was pressed. */
  readonly appointment: AppointmentView;
  /** Active display locale. */
  readonly locale: Locale;
  /** Localized label of the Back control. */
  readonly backLabel: string;
  /** Returns to the list without changing any command state. */
  readonly onBack: () => void;
}

/**
 * Show one appointment's facility, time, status and confirmation context.
 *
 * @param props the selected row, locale, Back label and handler
 * @returns the detail card with a labelled Back control
 */
export function AppointmentDetail({
  appointment,
  locale,
  backLabel,
  onBack,
}: AppointmentDetailProps): React.JSX.Element {
  const time = `${appointment.dateLabel ?? ''} ${appointment.timeLabel ?? appointment.localStart} (${appointment.timeZone})`.trim();
  return (
    <Card testID="appointment-detail">
      <Text accessibilityRole="header" accessibilityLanguage={locale} style={styles.heading}>
        {screenText(locale, 'appointmentDetails')}
      </Text>
      <Text testID="detail-time" accessibilityLanguage={locale} style={styles.value}>{time}</Text>
      <Text testID="detail-facility" accessibilityLanguage={locale} style={styles.value}>
        {appointment.facilityLine ?? appointment.status}
      </Text>
      <Text testID="detail-status" accessibilityLabel={appointment.status} accessibilityLanguage={locale} style={styles.value}>
        {appointment.status}
      </Text>
      {appointment.confirmedLocal === null ? null : (
        <Text testID="detail-confirmed" accessibilityLanguage={locale} style={styles.value}>
          {`${screenText(locale, 'confirmed')} ${appointment.confirmedLocal}`}
        </Text>
      )}
      <Pressable
        testID="appointment-detail-back"
        accessibilityRole="button"
        accessibilityLabel={backLabel}
        accessibilityLanguage={locale}
        onPress={onBack}
        style={styles.back}>
        <Text accessibilityLanguage={locale}>{backLabel}</Text>
      </Pressable>
    </Card>
  );
}

const styles = StyleSheet.create({
  heading: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.headingMd },
  value: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.body },
  back: { minHeight: SCREEN_TOKENS.size.target, minWidth: SCREEN_TOKENS.size.target, justifyContent: 'center' },
});
