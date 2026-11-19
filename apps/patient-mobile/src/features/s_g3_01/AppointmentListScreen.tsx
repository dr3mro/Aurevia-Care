import { Pressable, Text, View } from 'react-native';

import type { AppointmentView } from './appointments';
import type { DeviceToken, GenericNotice } from './types';

/** Props for the patient appointment list (PAT-01). */
export interface AppointmentListScreenProps {
  /** Server appointment projections prepared for display. */
  readonly appointments: readonly AppointmentView[];
  /** Fixed generic notice; never carries clinical detail. */
  readonly notice: GenericNotice;
  /** Registered devices for the active organization. */
  readonly devices: readonly DeviceToken[];
  /** Called when the patient confirms an eligible appointment. */
  readonly onConfirm: (appointmentId: string) => void;
  /** Called to request authenticated detail retrieval. */
  readonly onOpenDetails: (appointmentId: string) => void;
  /** Called when the patient revokes one device. */
  readonly onRevokeDevice: (deviceId: string) => void;
}

/**
 * Patient appointment list with a generic notification banner, confirmation
 * affordance and device revocation (PAT-01, OPS-02).
 *
 * Navigation and commands are typed callbacks: the screen has no navigation
 * dependency and performs no network or clinical decision of its own.
 *
 * @param props view state and callbacks supplied by the authenticated app
 * @returns the rendered screen
 */
export function AppointmentListScreen({
  appointments,
  notice,
  devices,
  onConfirm,
  onOpenDetails,
  onRevokeDevice,
}: AppointmentListScreenProps) {
  return (
    <View testID="appointment-list">
      <Text testID="generic-notice">{`${notice.title} — ${notice.body}`}</Text>
      {appointments.length === 0 ? (
        <Text testID="empty-appointments">No appointments</Text>
      ) : (
        appointments.map((row) => (
          <View key={row.key} testID={`appointment-${row.id}`}>
            <Text testID={`status-${row.id}`}>{row.status}</Text>
            <Text testID={`time-${row.id}`}>{`${row.localStart} (${row.timeZone})`}</Text>
            {row.confirmation.kind === 'pending' ? (
              <Pressable testID={`confirm-${row.id}`} onPress={() => onConfirm(row.id)}>
                <Text>Confirm</Text>
              </Pressable>
            ) : null}
            {row.confirmation.kind === 'confirmed' ? (
              <Text testID={`confirmed-${row.id}`}>Confirmed</Text>
            ) : null}
            <Pressable testID={`details-${row.id}`} onPress={() => onOpenDetails(row.id)}>
              <Text>View details</Text>
            </Pressable>
          </View>
        ))
      )}
      {devices
        .filter((device) => device.state === 'active')
        .map((device) => (
          <Pressable
            key={device.id}
            testID={`revoke-${device.id}`}
            onPress={() => onRevokeDevice(device.id)}
          >
            <Text>Revoke device</Text>
          </Pressable>
        ))}
    </View>
  );
}
