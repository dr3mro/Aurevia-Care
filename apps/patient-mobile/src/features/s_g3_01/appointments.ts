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
  readonly timeZone: string;
  readonly localStart: string;
  readonly status: AppointmentStatus;
  readonly confirmation: ConfirmationState;
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
 * @returns an immutable display row
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
    timeZone: appointment.facilityTimeZone,
    localStart: formatter.format(appointment.startsAt, appointment.facilityTimeZone),
    status: appointment.status,
    confirmation,
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
