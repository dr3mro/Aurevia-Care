import type { PersonName } from '../../i18n/format';
import { syntheticOrganizations } from '../../fixtures/synthetic';
import type { Appointment, BookingRequest } from './domain';

/** Monday anchor used by the day and week worklist fixtures. */
export const schedulingAnchorCivilDate = '2026-03-09';

/** The two synthetic tenants; their local identifiers intentionally collide. */
export const alphaOrganizationId = required(syntheticOrganizations[0]).id;
export const betaOrganizationId = required(syntheticOrganizations[1]).id;

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

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
