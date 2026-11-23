import type { PersonName } from '../../i18n/format';

/**
 * Visit states for a synthetic appointment (CLN-01). The forward chain is
 * booked -> checked-in -> in-progress -> completed; cancelled and no-show are
 * terminal branches. No real patient data reaches this module.
 */
export type VisitState =
  'booked' | 'checked-in' | 'in-progress' | 'completed' | 'cancelled' | 'no-show';

/** Day or week scope of the synthetic staff worklist (CLN-02). */
export type WorklistRange = 'day' | 'week';

/** A synthetic appointment carrying the tenant key and a tenant-local id. */
export interface Appointment {
  /** Organization identifier; always the first component of a row identity. */
  organizationId: string;
  /** Tenant-local identifier; two tenants may use the same value. */
  id: string;
  /** Synthetic patient name with an optional local-script form. */
  patientName: PersonName;
  /** UTC instant of the visit. */
  scheduledAt: string;
  /** IANA zone used only for display formatting. */
  timeZone: string;
  facilityCode: string;
  /** Protected clinician or room local identifier. */
  resourceId: string;
  visitType: string;
  state: VisitState;
  /** Synthetic assignee subject, absent while unassigned. */
  assignee?: string;
  /** Optimistic version, advanced by every synthetic command. */
  version: number;
}

/** A protected-resource booking request bound to one active organization. */
export interface BookingRequest {
  organizationId: string;
  resourceId: string;
  scheduledAt: string;
}

/** A command applied to a synthetic appointment. */
export type SchedulingCommand = { kind: 'check-in' } | { kind: 'assign'; assignee: string };

/** Thrown when a synthetic command would move a visit outside its lifecycle. */
export class IllegalVisitTransition extends Error {
  /** Record the source and rejected target states in the failure message. */
  constructor(from: VisitState, to: VisitState) {
    super(`Illegal visit transition: ${from} -> ${to}`);
    this.name = 'IllegalVisitTransition';
  }
}

/** States that still occupy a protected slot. */
const ACTIVE_STATES: ReadonlySet<VisitState> = new Set<VisitState>([
  'booked',
  'checked-in',
  'in-progress',
]);

/**
 * Return one tenant's appointments in scheduled-time order, with the local id
 * as a stable tie-break. This is the single tenant boundary of the UI; callers
 * never need to compare local identifiers across organizations.
 */
export function appointmentsForTenant(
  appointments: readonly Appointment[],
  organizationId: string,
): Appointment[] {
  return appointments
    .filter((appointment) => appointment.organizationId === organizationId)
    .sort((left, right) => {
      const byInstant = Date.parse(left.scheduledAt) - Date.parse(right.scheduledAt);
      return byInstant !== 0 ? byInstant : left.id.localeCompare(right.id);
    });
}

/**
 * Decide whether an appointment falls in the requested worklist range. Day
 * matching uses the UTC civil date of the instant; the week window starts on
 * Monday and is exclusive of the following Monday.
 */
export function withinRange(
  appointment: Appointment,
  range: WorklistRange,
  anchorCivilDate: string,
): boolean {
  const appointmentCivilDate = appointment.scheduledAt.slice(0, 10);
  if (range === 'day') {
    return appointmentCivilDate === anchorCivilDate;
  }
  const anchor = new Date(`${anchorCivilDate}T00:00:00Z`);
  const weekday = anchor.getUTCDay();
  const mondayOffset = weekday === 0 ? -6 : 1 - weekday;
  const start = new Date(anchor);
  start.setUTCDate(start.getUTCDate() + mondayOffset);
  const end = new Date(start);
  end.setUTCDate(end.getUTCDate() + 7);
  const instant = Date.parse(`${appointmentCivilDate}T00:00:00Z`);
  return instant >= start.getTime() && instant < end.getTime();
}

/** Scope, filter and order the appointments shown by a worklist screen. */
export function selectWorklist(
  appointments: readonly Appointment[],
  organizationId: string,
  range: WorklistRange,
  anchorCivilDate: string,
): Appointment[] {
  return appointmentsForTenant(appointments, organizationId).filter((appointment) =>
    withinRange(appointment, range, anchorCivilDate),
  );
}

/** List the states a visit may legally enter from the given state. */
export function nextVisitStates(state: VisitState): readonly VisitState[] {
  switch (state) {
    case 'booked':
      return ['checked-in', 'cancelled', 'no-show'];
    case 'checked-in':
      return ['in-progress', 'cancelled', 'no-show'];
    case 'in-progress':
      return ['completed'];
    case 'completed':
    case 'cancelled':
    case 'no-show':
      return [];
  }
}

/** A visit cannot be reassigned once it has reached a terminal state. */
export function isTerminal(state: VisitState): boolean {
  return state === 'completed' || state === 'cancelled' || state === 'no-show';
}

/** Apply a legal transition, throwing when the lifecycle forbids it. */
export function transitionVisitState(from: VisitState, to: VisitState): VisitState {
  if (!nextVisitStates(from).includes(to)) {
    throw new IllegalVisitTransition(from, to);
  }
  return to;
}

/** Apply a synthetic command and advance the optimistic version. */
export function applyCommand(appointment: Appointment, command: SchedulingCommand): Appointment {
  switch (command.kind) {
    case 'check-in': {
      const state = transitionVisitState(appointment.state, 'checked-in');
      return { ...appointment, state, version: appointment.version + 1 };
    }
    case 'assign': {
      if (isTerminal(appointment.state)) {
        throw new Error('Cannot assign a terminal visit');
      }
      return { ...appointment, assignee: command.assignee, version: appointment.version + 1 };
    }
  }
}

/**
 * Replace one appointment, requiring both the tenant key and the local id.
 * Equal local ids in two tenants are therefore updated independently.
 */
export function replaceAppointment(
  appointments: readonly Appointment[],
  organizationId: string,
  id: string,
  update: (appointment: Appointment) => Appointment,
): Appointment[] {
  return appointments.map((appointment) =>
    appointment.organizationId === organizationId && appointment.id === id
      ? update(appointment)
      : appointment,
  );
}

/**
 * Find an active same-tenant occupant of a protected slot. A record with the
 * same local resource id in another tenant is deliberately not a conflict.
 */
export function conflictingAppointment(
  appointments: readonly Appointment[],
  request: BookingRequest,
): Appointment | undefined {
  return appointments.find(
    (appointment) =>
      appointment.organizationId === request.organizationId &&
      appointment.resourceId === request.resourceId &&
      appointment.scheduledAt === request.scheduledAt &&
      ACTIVE_STATES.has(appointment.state),
  );
}
