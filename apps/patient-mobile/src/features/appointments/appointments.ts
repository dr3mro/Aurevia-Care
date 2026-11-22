import { tenantKey } from './tenancy';
import type {
  Appointment,
  AppointmentStatus,
  ConfirmationState,
  OrganizationId,
  ZoneFormatter,
} from './types';

/** One appointment row prepared for display. */
export interface AppointmentView {
  readonly key: string;
  readonly id: Appointment['id'];
  /** Version of the row that was read, echoed back on a versioned confirm. */
  readonly version: Appointment['version'];
  readonly timeZone: string;
  readonly localStart: string;
  readonly status: AppointmentStatus;
  readonly confirmation: ConfirmationState;
  /** Recorded confirmation instant rendered in the facility zone, when confirmed. */
  readonly confirmedLocal: string | null;
  /** Civil date label prepared by the authenticated loader, when supplied. */
  readonly dateLabel?: string;
  /** Local clock time prepared by the authenticated loader, when supplied. */
  readonly timeLabel?: string;
  /** Clinician or visit line, when the server projection includes it. */
  readonly visitLine?: string;
  /** Facility name and site line, when the server projection includes it. */
  readonly facilityLine?: string;
}

/**
 * Project one appointment for the patient list.
 *
 * The facility time zone and status come from the server; the client never
 * invents a status or guesses a zone from an offset.
 *
 * @param appointment server appointment projection
 * @param organizationId active organization used to scope the row key
 * @param formatter injected time-zone formatter
 * @param confirmation derived confirmation affordance
 * @returns an immutable display row; when the visit is confirmed, the recorded
 *   instant is rendered in the same facility zone as the appointment start
 */
export function appointmentView(
  appointment: Appointment,
  organizationId: OrganizationId,
  formatter: ZoneFormatter,
  confirmation: ConfirmationState,
): AppointmentView {
  return {
    key: tenantKey(organizationId, appointment.id),
    id: appointment.id,
    version: appointment.version,
    timeZone: appointment.facilityTimeZone,
    localStart: formatter.format(appointment.startsAt, appointment.facilityTimeZone),
    status: appointment.status,
    confirmation,
    confirmedLocal:
      confirmation.kind === 'confirmed'
        ? formatter.format(confirmation.confirmedAt, appointment.facilityTimeZone)
        : null,
  };
}

/**
 * Apply a server confirmation reply to one displayed row.
 *
 * The instant comes from the server reply, never from the device clock, and is
 * rendered in the same facility zone as the appointment start so the confirmed
 * time and the visit time share one frame of reference. The version advances to
 * the one the server returned, keeping any later command precondition correct.
 *
 * @param row the appointment row that was confirmed
 * @param confirmedAt server instant of the confirmation
 * @param version server version after the confirmation
 * @param formatter injected facility-zone formatter
 * @returns a new immutable display row in the confirmed state
 */
export function confirmedAppointmentView(
  row: AppointmentView,
  confirmedAt: string,
  version: number,
  formatter: ZoneFormatter,
): AppointmentView {
  return {
    ...row,
    version,
    confirmation: { kind: 'confirmed', confirmedAt },
    confirmedLocal: formatter.format(confirmedAt, row.timeZone),
  };
}

/**
 * Order appointments by their UTC start without mutating the source list.
 *
 * @param appointments appointment projections
 * @returns a new list in ascending start order
 */
export function sortByScheduled<T extends { readonly startsAt: string }>(
  appointments: readonly T[],
): T[] {
  return [...appointments].sort((left, right) => left.startsAt.localeCompare(right.startsAt));
}
