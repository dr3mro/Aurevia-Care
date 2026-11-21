import { Pressable, StyleSheet, Text } from 'react-native';

import type { AppointmentView } from './appointments';
import {
  Card,
  PrimaryButton,
  resolveScreenLocale,
  screenText,
  ScreenFrame,
  SCREEN_TOKENS,
  StatusPill,
} from './patientDesign';
import type { Locale, PillVariant, TabId } from './patientDesign';
import type { DeviceToken, GenericNotice } from './types';

/** Props for the patient appointment list (PAT-01). */
export interface AppointmentListScreenProps {
  /** Server appointment projections prepared for display. */
  readonly appointments: readonly AppointmentView[];
  /** Fixed generic notice; never carries clinical detail. */
  readonly notice: GenericNotice;
  /** Verified patient whose devices may be shown and revoked (PRD-03). */
  readonly patientId: string;
  /** Registered devices for the active organization. */
  readonly devices: readonly DeviceToken[];
  /** Called when the patient confirms an eligible appointment. */
  readonly onConfirm: (appointmentId: string) => void;
  /** Called to request authenticated detail retrieval. */
  readonly onOpenDetails: (appointmentId: string) => void;
  /** Called when the patient revokes one device. */
  readonly onRevokeDevice: (deviceId: string) => void;
  /** Active display locale; defaults to English for embedders without a locale prop. */
  readonly locale?: Locale;
  /** Render the standalone phone-frame tab bar for a design review. */
  readonly showTabBar?: boolean;
  /** Highlighted standalone tab. */
  readonly activeTab?: TabId;
  /** Standalone tab handler. */
  readonly onTab?: (tab: TabId) => void;
}

/** Status word and icon role for one card; colour is reinforcement only. */
interface StatusPresentation {
  readonly label: string;
  readonly variant: PillVariant;
}

/** Derive the human-readable status pill from confirmation and lifecycle state. */
function statusPresentation(row: AppointmentView, locale: Locale): StatusPresentation {
  if (row.confirmation.kind === 'pending') {
    return { label: screenText(locale, 'pleaseConfirm'), variant: 'warning' };
  }
  if (row.confirmation.kind === 'confirmed') {
    return {
      label: `${screenText(locale, 'confirmed')} ${row.confirmedLocal}`.trim(),
      variant: 'success',
    };
  }
  if (row.status === 'completed') {
    return { label: screenText(locale, 'completed'), variant: 'neutral' };
  }
  return { label: row.status, variant: 'neutral' };
}

/**
 * Patient appointment list with a generic notification banner, confirmation
 * affordance and device revocation (PAT-01, OPS-02).
 *
 * Navigation and commands are typed callbacks: the screen has no navigation
 * dependency and performs no network or clinical decision of its own. Display
 * copy and status words come from the shared token-backed design module so the
 * screen renders in English and Arabic without raw colours.
 *
 * @param props view state and callbacks supplied by the authenticated app
 * @returns the rendered screen
 */
export function AppointmentListScreen({
  appointments,
  notice,
  patientId,
  devices,
  onConfirm,
  onOpenDetails,
  onRevokeDevice,
  locale = resolveScreenLocale(),
  showTabBar = false,
  activeTab = 'appointments',
  onTab,
}: AppointmentListScreenProps) {
  return (
    <ScreenFrame
      testID="appointment-list"
      locale={locale}
      title={screenText(locale, 'appointmentsTitle')}
      subtitle={screenText(locale, 'appointmentsSubtitle')}
      showTabBar={showTabBar}
      activeTab={activeTab}
      onTab={onTab}>
      <Text testID="generic-notice" accessibilityLanguage={locale} style={styles.notice}>
        {`${notice.title} — ${notice.body}`}
      </Text>
      {appointments.length === 0 ? (
        <Text testID="empty-appointments" accessibilityLanguage={locale}>
          {screenText(locale, 'noAppointments')}
        </Text>
      ) : null}
      {appointments.map((row) => {
        const status = statusPresentation(row, locale);
        const time = `${row.dateLabel ?? ''} ${row.timeLabel ?? row.localStart} (${row.timeZone})`.trim();
        const facility = `${row.facilityLine ?? ''} ${row.timeZone}`.trim();
        return (
          <Card key={row.key} testID={`appointment-${row.id}`}>
            <StatusPill
              testID={`status-pill-${row.id}`}
              variant={status.variant}
              label={status.label}
              locale={locale}
            />
            <Text testID={`time-${row.id}`} accessibilityLanguage={locale} style={styles.dateTime}>
              {time}
            </Text>
            {row.visitLine === undefined ? null : (
              <Text accessibilityLabel={`visit-${row.id}`} accessibilityLanguage={locale} style={styles.visitLine}>
                {row.visitLine}
              </Text>
            )}
            <Text accessibilityLabel={`facility-${row.id}`} accessibilityLanguage={locale} style={styles.facility}>
              {facility}
            </Text>
            {/* Kept as the authoritative machine status; the pill above is the
                patient-facing word with a shape-distinct icon (UX-01). */}
            <Text testID={`status-${row.id}`} accessibilityLabel={status.label} accessibilityLanguage={locale} style={styles.statusCode}>
              {row.status}
            </Text>
            {row.confirmation.kind === 'pending' ? (
              <PrimaryButton
                testID={`confirm-${row.id}`}
                label={screenText(locale, 'confirmAppointment')}
                locale={locale}
                onPress={() => onConfirm(row.id)}
              />
            ) : null}
            {row.confirmation.kind === 'confirmed' ? (
              <Text testID={`confirmed-${row.id}`} accessibilityLanguage={locale} style={styles.confirmed}>
                {`${screenText(locale, 'confirmed')} at ${row.confirmedLocal}`}
              </Text>
            ) : null}
            <Pressable
              testID={`details-${row.id}`}
              accessibilityRole="button"
              accessibilityLabel={screenText(locale, 'viewDetails')}
              accessibilityLanguage={locale}
              onPress={() => onOpenDetails(row.id)}
              style={styles.details}>
              <Text accessibilityLanguage={locale}>{screenText(locale, 'viewDetails')}</Text>
            </Pressable>
          </Card>
        );
      })}
      {devices
        .filter((device) => device.state === 'active' && device.patientId === patientId)
        .map((device) => (
          <Pressable
            key={device.id}
            testID={`revoke-${device.id}`}
            accessibilityRole="button"
            accessibilityLabel={screenText(locale, 'revokeDevice')}
            accessibilityLanguage={locale}
            onPress={() => onRevokeDevice(device.id)}
            style={styles.details}>
            <Text accessibilityLanguage={locale}>{screenText(locale, 'revokeDevice')}</Text>
          </Pressable>
        ))}
    </ScreenFrame>
  );
}

const styles = StyleSheet.create({
  notice: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.label, marginBottom: SCREEN_TOKENS.space.four },
  dateTime: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.numericLg, fontWeight: SCREEN_TOKENS.font.semibold },
  visitLine: { color: SCREEN_TOKENS.color.textPrimary, fontSize: SCREEN_TOKENS.font.body },
  facility: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.label },
  statusCode: { color: SCREEN_TOKENS.color.textSecondary, fontSize: SCREEN_TOKENS.font.caption },
  confirmed: { color: SCREEN_TOKENS.color.successText, fontSize: SCREEN_TOKENS.font.label },
  details: { minHeight: SCREEN_TOKENS.size.target, justifyContent: 'center' },
});
