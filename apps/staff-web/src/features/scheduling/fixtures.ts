import { defined } from '../../components/invariant';
import type { PersonName } from '../../i18n/format';
import { syntheticOrganizations, syntheticTimeZone } from '../../fixtures/synthetic';
import type {
  Appointment,
  BookingRequest,
  FreeSlot,
  PatientIdentity,
  ReceptionFacility,
  StaleSlot,
} from './domain';

/** Monday anchor used by the day and week worklist fixtures. */
export const schedulingAnchorCivilDate = '2026-03-09';

/** The two synthetic tenants; their local identifiers intentionally collide. */
export const alphaOrganizationId = defined(syntheticOrganizations[0], 'alpha organization').id;
export const betaOrganizationId = defined(syntheticOrganizations[1], 'beta organization').id;

/** Synthetic walk-in used when the form creates a local booking. */
const syntheticWalkInPatient: PersonName = {
  latinName: 'Synthetic Walk-in',
  localName: 'مريض تجريبي',
};

/** Synthetic alpha appointments, deliberately not in scheduled-time order. */
export const syntheticAlphaAppointments: readonly Appointment[] = [
  {
    id: 'apt-001',
    organizationId: alphaOrganizationId,
    patientName: { latinName: 'Synthetic Layla', localName: 'ليلى تجريبية' },
    scheduledAt: '2026-03-09T10:30:00Z',
    timeZone: 'America/New_York',
    facilityCode: 'MAIN',
    resourceId: 'room-1',
    visitType: 'Synthetic follow-up',
    state: 'booked',
    version: 1,
  },
  {
    id: 'apt-002',
    organizationId: alphaOrganizationId,
    patientName: { latinName: 'Synthetic Omar', localName: 'عمر تجريبي' },
    scheduledAt: '2026-03-09T09:00:00Z',
    timeZone: 'America/New_York',
    facilityCode: 'MAIN',
    resourceId: 'clinician-7',
    visitType: 'Synthetic new visit',
    state: 'checked-in',
    assignee: 'synthetic.reception',
    version: 2,
  },
  {
    id: 'apt-003',
    organizationId: alphaOrganizationId,
    patientName: { latinName: 'Synthetic Sara', localName: 'سارة تجريبية' },
    scheduledAt: '2026-03-09T08:15:00Z',
    timeZone: 'America/New_York',
    facilityCode: 'MAIN',
    resourceId: 'room-1',
    visitType: 'Synthetic intake',
    state: 'in-progress',
    version: 3,
  },
  {
    id: 'apt-004',
    organizationId: alphaOrganizationId,
    patientName: { latinName: 'Synthetic Nadia', localName: 'نادية تجريبية' },
    scheduledAt: '2026-03-10T08:15:00Z',
    timeZone: 'America/New_York',
    facilityCode: 'MAIN',
    resourceId: 'room-2',
    visitType: 'Synthetic review',
    state: 'completed',
    version: 4,
  },
  {
    id: 'apt-005',
    organizationId: alphaOrganizationId,
    patientName: { latinName: 'Synthetic Faisal', localName: 'فيصل تجريبي' },
    scheduledAt: '2026-03-11T08:15:00Z',
    timeZone: 'America/New_York',
    facilityCode: 'MAIN',
    resourceId: 'room-1',
    visitType: 'Synthetic follow-up',
    state: 'cancelled',
    version: 2,
  },
  {
    id: 'apt-006',
    organizationId: alphaOrganizationId,
    patientName: { latinName: 'Synthetic Hana', localName: 'هناء تجريبية' },
    scheduledAt: '2026-03-12T08:15:00Z',
    timeZone: 'America/New_York',
    facilityCode: 'MAIN',
    resourceId: 'room-1',
    visitType: 'Synthetic intake',
    state: 'no-show',
    version: 2,
  },
  {
    id: 'apt-007',
    organizationId: alphaOrganizationId,
    patientName: { latinName: 'Synthetic Nora', localName: 'نورة تجريبية' },
    scheduledAt: '2026-03-15T08:15:00Z',
    timeZone: 'America/New_York',
    facilityCode: 'MAIN',
    resourceId: 'room-2',
    visitType: 'Synthetic follow-up',
    state: 'booked',
    version: 1,
  },
  {
    id: 'apt-008',
    organizationId: alphaOrganizationId,
    patientName: { latinName: 'Synthetic Tom', localName: 'توم تجريبي' },
    scheduledAt: '2026-03-16T08:15:00Z',
    timeZone: 'America/New_York',
    facilityCode: 'MAIN',
    resourceId: 'room-2',
    visitType: 'Synthetic follow-up',
    state: 'booked',
    version: 1,
  },
];

/** Synthetic beta appointments reusing local ids and resource ids. */
export const syntheticBetaAppointments: readonly Appointment[] = [
  {
    id: 'apt-001',
    organizationId: betaOrganizationId,
    patientName: { latinName: 'Synthetic Reem', localName: 'ريم تجريبية' },
    scheduledAt: '2026-03-09T10:30:00Z',
    timeZone: 'Europe/London',
    facilityCode: 'LAB',
    resourceId: 'room-1',
    visitType: 'Synthetic collection',
    state: 'booked',
    version: 1,
  },
  {
    id: 'apt-002',
    organizationId: betaOrganizationId,
    patientName: { latinName: 'Synthetic Kareem', localName: 'كريم تجريبي' },
    scheduledAt: '2026-03-10T11:00:00Z',
    timeZone: 'Europe/London',
    facilityCode: 'LAB',
    resourceId: 'clinician-7',
    visitType: 'Synthetic collection',
    state: 'completed',
    version: 3,
  },
  {
    id: 'apt-009',
    organizationId: betaOrganizationId,
    patientName: { latinName: 'Synthetic Zain', localName: 'زين تجريبي' },
    scheduledAt: '2026-03-20T09:00:00Z',
    timeZone: 'Europe/London',
    facilityCode: 'LAB',
    resourceId: 'room-1',
    visitType: 'Synthetic collection',
    state: 'booked',
    version: 1,
  },
];

/** The full synthetic pool, with two tenants sharing identical local ids. */
export const syntheticAppointments: readonly Appointment[] = [
  ...syntheticAlphaAppointments,
  ...syntheticBetaAppointments,
];

/** Fixed synthetic instant backing every time-dependent reception display. */
export const receptionNow = '2026-03-09T09:30:00Z';

/** Synthetic facilities with stable codes and display time zones. */
export const receptionFacilities: readonly ReceptionFacility[] = [
  {
    code: 'MAIN',
    name: 'Harbor Family Clinic, Brooklyn site',
    timeZone: syntheticTimeZone,
  },
  {
    code: 'LAB',
    name: 'Harbor Family Clinic, Lab site',
    timeZone: syntheticTimeZone,
  },
];

/** Synthetic receptionist whose name is recorded on local check-ins. */
export const receptionChecker = 'Synthetic Receptionist';

/** Synthetic role and idle countdown shown in the navigation footer. */
export const receptionRole = 'Reception';
export const receptionIdleMinutes = 14;
export const receptionUnreadMessages = 3;
export const receptionLastUpdatedSeconds = 12;

/**
 * Synthetic identity context keyed by tenant and local appointment id. The
 * composite key prevents one tenant's medical record number from ever being
 * shown for another tenant's identically named local row.
 */
const receptionIdentityById: Readonly<Record<string, PatientIdentity>> = {
  [`${alphaOrganizationId}:apt-001`]: {
    mrn: '100233',
    ageYears: 45,
    dateOfBirth: '1981-03-04',
    language: 'ar',
    identifier: 'National ID',
    issuer: 'issuer verified',
    verified: true,
    selfPayBalance: 40,
    clinician: 'Dr. Noor Karim',
    room: 'Room 2',
  },
  [`${alphaOrganizationId}:apt-002`]: {
    mrn: '100877',
    ageYears: 7,
    dateOfBirth: '2019-05-12',
    language: 'en',
    identifier: 'Passport',
    issuer: 'issuer verified',
    verified: true,
    selfPayBalance: 0,
    clinician: 'Dr. Eli Park',
    room: 'Room 4',
  },
  [`${alphaOrganizationId}:apt-005`]: {
    mrn: '098761',
    ageYears: 71,
    dateOfBirth: '1955-01-19',
    language: 'ar',
    identifier: 'National ID',
    issuer: 'issuer not verified',
    verified: false,
    selfPayBalance: 12,
    clinician: 'Dr. Dana Ortiz',
    room: 'Room 3',
  },
  [`${betaOrganizationId}:apt-001`]: {
    mrn: '200411',
    ageYears: 33,
    dateOfBirth: '1993-07-08',
    language: 'en',
    identifier: 'Passport',
    issuer: 'issuer verified',
    verified: true,
    selfPayBalance: 25,
    clinician: 'Dr. Reem Reviewer',
    room: 'Lab 1',
  },
};

/**
 * Look up tenant-scoped identity context. Unknown local rows receive a clearly
 * synthetic fallback so a newly booked or API-loaded appointment can still be
 * shown without borrowing another tenant's identity.
 */
export function receptionIdentityFor(appointment: Appointment, organizationId: string): PatientIdentity {
  const stored = receptionIdentityById[`${organizationId}:${appointment.id}`];
  if (stored !== undefined) {
    return stored;
  }
  return {
    mrn: `synthetic-${appointment.id}`,
    ageYears: 0,
    dateOfBirth: '2000-01-01',
    language: 'en',
    identifier: 'Synthetic identifier',
    issuer: 'issuer not verified',
    verified: false,
    selfPayBalance: 0,
    clinician: appointment.resourceId,
    room: appointment.facilityCode,
  };
}

/** Nearest free alternatives offered after a protected slot is taken. */
const receptionFreeSlots: readonly FreeSlot[] = [
  { label: '10:30 AM', resourceId: 'room-3', scheduledAt: '2026-03-09T10:30:00Z' },
  { label: '10:45 AM', resourceId: 'room-3', scheduledAt: '2026-03-09T10:45:00Z' },
  { label: '11:00 AM', resourceId: 'room-4', scheduledAt: '2026-03-09T11:00:00Z' },
];

/** A just-taken protected slot and the alternatives offered by booking check. */
export const receptionStaleSlot: StaleSlot = {
  label: 'Room 3 at 10:15 AM',
  takenBy: 'another synthetic user',
  freeSlots: receptionFreeSlots,
};

/** Clinical context used when a free slot becomes a complete booking request. */
export const receptionWalkIn = {
  facilityCode: 'MAIN',
  patientId: 'synthetic-walk-in',
  visitType: 'Synthetic follow-up',
};

/** A checked-in row without an assignee, used to verify the handoff guard. */
export const receptionUnassignedCheckedIn: Appointment = {
  id: 'apt-010',
  organizationId: alphaOrganizationId,
  patientName: { latinName: 'Synthetic Unassigned', localName: 'غير مسند تجريبي' },
  scheduledAt: '2026-03-09T09:25:00Z',
  timeZone: syntheticTimeZone,
  facilityCode: 'MAIN',
  resourceId: 'room-6',
  visitType: 'Synthetic review',
  state: 'checked-in',
  version: 1,
};

/** Build a clearly synthetic booking bound to the requesting tenant. */
export function createSyntheticAppointment(request: BookingRequest): Appointment {
  return {
    id: `synthetic-booking:${request.organizationId}:${request.scheduledAt}:${request.resourceId}`,
    organizationId: request.organizationId,
    patientName: syntheticWalkInPatient,
    scheduledAt: request.scheduledAt,
    timeZone: 'UTC',
    facilityCode: 'SYN',
    resourceId: request.resourceId,
    visitType: 'Synthetic walk-in',
    state: 'booked',
    version: 1,
  };
}
