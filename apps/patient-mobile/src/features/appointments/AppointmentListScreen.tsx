import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import type { ViewStyle } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { windowClassFor } from '../../app/layout/useWindowClass';
import type { WindowClass } from '../../app/layout/useWindowClass';
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
  /**
   * Registered devices for the active organization. The host passes an
   * already tenant-scoped projection; this presentational screen filters only
   * delivery state and verified patient, so it never re-derives tenant scope
   * from a bare local id.
   */
  readonly devices: readonly DeviceToken[];
  /**
   * Called with the whole read row when the patient confirms an appointment.
   * The row carries the version the confirm command must echo.
   */
  readonly onConfirm: (appointment: AppointmentView) => void;
  /**
   * Appointment whose confirm command is in flight. All confirm controls are
   * disabled while any command is in flight: one command is processed at a
   * time, so an enabled control on another row would silently drop the press.
   */
  readonly pendingAppointmentId?: string | null;
  /** Called to request authenticated detail retrieval. */
  readonly onOpenDetails: (appointmentId: string) => void;
  /** Called when the patient revokes one device. */
  readonly onRevokeDevice: (deviceId: string) => void;
  /** Row whose details are open, so the chosen control can expose its state. */
  readonly selectedAppointmentId?: string | null;
  /** Detail or confirmation content supplied by the page for the detail pane. */
  readonly detailContent?: ReactNode;
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
 * Maximum inline width of the centred reading column. On a wide window the
 * content stops growing here instead of stretching to the full tablet width,
 * which keeps appointments readable at 200 percent text.
 */
const CONTENT_MAX_WIDTH = 960;

/** Safe-area fallback for a host that does not provide the inset hook. */
const EMPTY_INSETS = { top: 0, right: 0, bottom: 0, left: 0 };

/**
 * Resolve the safe-area hook once at module scope. Headless hosts and preview
 * renderers may not expose it; in that case the layout keeps its regular
 * spacing instead of failing to render.
 */
const readSafeAreaInsets = useSafeAreaInsets ?? (() => EMPTY_INSETS);

/** Style set that turns the current window into the responsive two-pane frame. */
interface ResponsiveStyles {
  readonly content: ViewStyle;
  readonly listPane: ViewStyle;
  readonly detailPane: ViewStyle;
}

/**
 * Derive the presentation-only layout styles from the current window.
 *
 * Width class decides the arrangement: large windows place the appointment
 * list and its detail pane side by side (`row`), smaller windows stack them
 * (`column`). Wrapping and zero minimum widths let a pane reflow instead of
 * clipping when text scales. Safe-area insets are physical screen edges, so
 * the inline pair is mapped once to logical start/end for the active direction
 * (Arabic swaps them). The panes then use only logical padding and margins and
 * mirror without a per-component direction branch. No clinical value, callback
 * or accessibility label is affected by this function.
 *
 * @param windowClass class derived from the current window width
 * @param fontScale system text scale, where 1 is 100% and 2 is 200%
 * @param insets safe-area insets at the four viewport edges
 * @param rtl whether the active layout direction is right-to-left
 * @returns styles for the content frame and its two panes
 */
function responsiveStyles(
  windowClass: WindowClass,
  fontScale: number,
  insets: typeof EMPTY_INSETS,
  rtl: boolean,
): ResponsiveStyles {
  const isLarge = windowClass === 'large';
  const paneFlex = isLarge ? 1 : undefined;
  const inlineStartInset = rtl ? insets.right : insets.left;
  const inlineEndInset = rtl ? insets.left : insets.right;
  return {
    content: {
      alignSelf: 'center',
      flexDirection: isLarge ? 'row' : 'column',
      flexWrap: isLarge ? 'wrap' : 'nowrap',
      maxWidth: CONTENT_MAX_WIDTH,
      width: '100%',
      paddingTop: Math.max(insets.top, 16),
      paddingBottom: Math.max(insets.bottom, 16),
      paddingStart: Math.max(inlineStartInset, 16),
      paddingEnd: Math.max(inlineEndInset, 16),
    },
    listPane: { flex: paneFlex, minWidth: 0 },
    detailPane: { flex: paneFlex, minWidth: 0, marginStart: isLarge ? paneGutter(fontScale) : 0 },
  };
}

/**
 * Gutter between the two panes grows with system text scale so larger text
 * keeps a readable separation instead of running the panes together.
 *
 * @param fontScale system text scale, where 1 is 100% and 2 is 200%
 * @returns the inline gutter in density-independent points
 */
function paneGutter(fontScale: number): number {
  if (fontScale >= 2) {
    return SCREEN_TOKENS.space.six;
  }
  if (fontScale >= 1.5) {
    return SCREEN_TOKENS.space.five;
  }
  return SCREEN_TOKENS.space.four;
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
  pendingAppointmentId,
  onOpenDetails,
  onRevokeDevice,
  selectedAppointmentId = null,
  detailContent,
  locale = resolveScreenLocale(),
  showTabBar = false,
  activeTab = 'appointments',
  onTab,
}: AppointmentListScreenProps) {
  const { width, fontScale } = useWindowDimensions();
  const panes = responsiveStyles(windowClassFor(width), fontScale, readSafeAreaInsets(), locale === 'ar');
  return (
    <ScreenFrame
      testID="appointment-list"
      locale={locale}
      title={screenText(locale, 'appointmentsTitle')}
      subtitle={screenText(locale, 'appointmentsSubtitle')}
      showTabBar={showTabBar}
      activeTab={activeTab}
      onTab={onTab}>
      <View testID="appointment-content" style={panes.content}>
        <View testID="appointment-list-pane" style={panes.listPane}>
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
                    disabled={pendingAppointmentId != null}
                    onPress={() => onConfirm(row)}
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
                  accessibilityState={{ selected: row.id === selectedAppointmentId }}
                  onPress={() => onOpenDetails(row.id)}
                  style={styles.details}>
                  <Text accessibilityLanguage={locale}>{screenText(locale, 'viewDetails')}</Text>
                </Pressable>
              </Card>
            );
          })}
        </View>
        <View testID="appointment-detail-pane" style={panes.detailPane}>
          {detailContent}
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
        </View>
      </View>
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
  details: {
    minHeight: SCREEN_TOKENS.size.target,
    minWidth: SCREEN_TOKENS.size.target,
    justifyContent: 'center',
  },
});
