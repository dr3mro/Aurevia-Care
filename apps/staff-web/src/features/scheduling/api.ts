import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { send } from '../../api/transport';
import type { WorklistRange } from './domain';
import type { Appointment, VisitState } from './domain';

/** The authoritative wire states of one appointment (CLN-01). */
export type ApiVisitState =
  | 'held'
  | 'booked'
  | 'checked_in'
  | 'in_progress'
  | 'completed'
  | 'cancelled'
  | 'no_show'
  | 'expired';

/** The appointment projection returned by the real API. */
export interface AppointmentResponse {
  id: string;
  version: number;
  patientId: string;
  facilityCode: string;
  clinicianCode: string | null;
  roomCode: string | null;
  visitType: string;
  startsAt: string;
  endsAt: string;
  state: ApiVisitState;
  seriesId: string | null;
  expiresAt: string | null;
  overrideReason: string | null;
}

/** One appointment and its append-only history as returned by the API. */
export interface AppointmentViewResponse {
  appointment: AppointmentResponse;
  history: readonly unknown[];
}

/** One cursor-paginated page of appointment views. */
export interface AppointmentPageResponse {
  items: readonly AppointmentViewResponse[];
  nextCursor: string | null;
}

/** Query supported by `GET /api/v1/appointments` (READY-13). */
export interface AppointmentListQuery {
  from: string;
  to: string;
  cursor?: string | undefined;
  resource?: string | undefined;
  state?: ApiVisitState | undefined;
  limit?: number | undefined;
}

/** Body of `POST /api/v1/appointments`; tenant and author are never sent. */
export interface ApiBookingBody {
  facilityCode: string;
  patientId: string;
  visitType: string;
  clinicianCode: string | null;
  roomCode: string | null;
  startsAt: string;
  overrideReason: string | null;
}

/** Wire-to-domain state map; holds and expirations map to the nearest UI state. */
const stateMap: Record<ApiVisitState, VisitState> = {
  held: 'booked',
  booked: 'booked',
  checked_in: 'checked-in',
  in_progress: 'in-progress',
  completed: 'completed',
  cancelled: 'cancelled',
  no_show: 'no-show',
  expired: 'cancelled',
};

/** Format a UTC-midnight date as an ISO instant without milliseconds. */
function isoDate(date: Date): string {
  return `${date.toISOString().slice(0, 10)}T00:00:00Z`;
}

/**
 * Resolve the half-open UTC instant window of a day or week worklist. A week
 * starts on Monday; a Sunday anchor resolves to the week that just ended, the
 * same rule the synthetic domain uses.
 *
 * @param range day or week scope shown by the screen
 * @param anchorCivilDate civil `YYYY-MM-DD` that anchors the window
 * @returns inclusive lower and upper query instants
 */
export function rangeWindow(
  range: WorklistRange,
  anchorCivilDate: string,
): { from: string; to: string } {
  const anchor = new Date(`${anchorCivilDate}T00:00:00Z`);
  if (range === 'day') {
    const dayEnd = new Date(anchor);
    dayEnd.setUTCDate(dayEnd.getUTCDate() + 1);
    return { from: isoDate(anchor), to: isoDate(dayEnd) };
  }
  const weekday = anchor.getUTCDay();
  const mondayOffset = weekday === 0 ? -6 : 1 - weekday;
  const start = new Date(anchor);
  start.setUTCDate(start.getUTCDate() + mondayOffset);
  const end = new Date(start);
  end.setUTCDate(end.getUTCDate() + 7);
  return { from: isoDate(start), to: isoDate(end) };
}

/**
 * Build a descriptor for an appointment command. The command sub-paths are not
 * published in the generated contract yet, so only the URL is substituted;
 * tenant, correlation, version and idempotency headers stay owned by the shared
 * request builder.
 */
function commandDescriptor(
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor('POST', '/api/v1/appointments', meta, body);
  return { ...base, url: path };
}

/**
 * Fetch one page of appointments in the active organization.
 *
 * @throws TransportError for every non-ok, offline or timed-out response.
 */
export async function listAppointments(
  query: AppointmentListQuery,
  organizationId: string,
): Promise<AppointmentPageResponse> {
  const base = buildRequestDescriptor('GET', '/api/v1/appointments', {
    activeOrganization: organizationId,
  });
  const params = new URLSearchParams({ from: query.from, to: query.to });
  if (query.cursor !== undefined) {
    params.set('cursor', query.cursor);
  }
  if (query.resource !== undefined) {
    params.set('resource', query.resource);
  }
  if (query.state !== undefined) {
    params.set('state', query.state);
  }
  if (query.limit !== undefined) {
    params.set('limit', String(query.limit));
  }
  return send<AppointmentPageResponse>({ ...base, url: `${base.url}?${params.toString()}` });
}

/** Book a visit with the clinical context entered by the reception user. */
export async function book(
  body: ApiBookingBody,
  organizationId: string,
): Promise<AppointmentViewResponse> {
  return send<AppointmentViewResponse>(
    commandDescriptor(
      '/api/v1/appointments',
      {
        activeOrganization: organizationId,
        idempotencyKey: crypto.randomUUID(),
      },
      { ...body },
    ),
  );
}

/** Check a patient in, guarded by the appointment version. */
export async function checkIn(
  id: string,
  version: number,
  organizationId: string,
): Promise<AppointmentViewResponse> {
  return send<AppointmentViewResponse>(
    commandDescriptor(`/api/v1/appointments/${encodeURIComponent(id)}/check-in`, {
      activeOrganization: organizationId,
      ifMatch: version,
    }),
  );
}

/** Cancel an appointment with a recorded reason and a version precondition. */
export async function cancel(
  id: string,
  version: number,
  reason: string,
  organizationId: string,
): Promise<AppointmentViewResponse> {
  return send<AppointmentViewResponse>(
    commandDescriptor(
      `/api/v1/appointments/${encodeURIComponent(id)}/cancel`,
      { activeOrganization: organizationId, ifMatch: version },
      { reason },
    ),
  );
}

/**
 * Convert one API appointment view into the existing display type, scoped to
 * the active organization. The projection has no patient name or facility zone,
 * so the opaque patient id is shown as the name and instants are labelled UTC.
 */
export function toAppointment(view: AppointmentViewResponse, organizationId: string): Appointment {
  const appointment = view.appointment;
  return {
    organizationId,
    id: appointment.id,
    patientName: { latinName: appointment.patientId },
    scheduledAt: appointment.startsAt,
    timeZone: 'UTC',
    facilityCode: appointment.facilityCode,
    resourceId: appointment.clinicianCode ?? appointment.roomCode ?? '',
    visitType: appointment.visitType,
    state: stateMap[appointment.state],
    version: appointment.version,
  };
}
