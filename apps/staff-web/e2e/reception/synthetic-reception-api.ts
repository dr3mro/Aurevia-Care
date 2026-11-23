import type { Page, Route } from '@playwright/test';

/**
 * Deterministic in-browser stand-in for the reception boundaries (WEB-10,
 * CLN-01, IAM-03). The harness mounts the real patients and scheduling pages,
 * but a browser journey must not contact a patient record or a scheduler. This
 * stub intercepts only `/api/v1/patients` and `/api/v1/appointments/**` from one
 * page, mints opaque synthetic identifiers and records every command so the
 * journey can assert what the browser actually sent.
 */

/** One synthetic patient projection returned to the registration page. */
interface SyntheticPatient {
  id: string;
  version: number;
  mrn: string;
  mrnIssuer: string;
  displayName: string;
  normalizedName: string;
  dob: string | null;
  dobUnknownReason: string | null;
  language: string;
  contacts: string;
  identifiers: string;
  consents: string;
}

/** One synthetic appointment in the wire shape the scheduling page consumes. */
interface SyntheticAppointment {
  id: string;
  version: number;
  patientId: string;
  facilityCode: string;
  clinicianCode: string | null;
  roomCode: string | null;
  visitType: string;
  startsAt: string;
  endsAt: string;
  state: string;
  seriesId: string | null;
  expiresAt: string | null;
  overrideReason: string | null;
}

/** Mutable state shared with the page's route handlers; safe to assert in the test. */
export interface SyntheticReceptionApi {
  /** `METHOD /path` for every intercepted request, in arrival order. */
  calls: string[];
  /** Patients registered during this browser journey. */
  patients: SyntheticPatient[];
  /** Appointments visible to the worklist during this browser journey. */
  appointments: SyntheticAppointment[];
}

/** The one seeded booked visit, so the check-in action is available immediately. */
const seedAppointment: SyntheticAppointment = {
  id: 'apt-seed-1',
  version: 1,
  patientId: 'patient-seed',
  facilityCode: 'MAIN',
  clinicianCode: 'room-1',
  roomCode: null,
  visitType: 'Synthetic intake',
  startsAt: '2026-03-09T08:15:00Z',
  endsAt: '2026-03-09T08:45:00Z',
  state: 'booked',
  seriesId: null,
  expiresAt: null,
  overrideReason: null,
};

/** Respond with a JSON body through the intercepted route. */
async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/** Read one string field from a posted body without trusting its runtime type. */
function text(body: Record<string, unknown>, key: string, fallback: string): string {
  const value = body[key];
  return typeof value === 'string' ? value : fallback;
}

/** Wrap one appointment in the view envelope the scheduling page expects. */
function asView(appointment: SyntheticAppointment): { appointment: SyntheticAppointment; history: [] } {
  return { appointment, history: [] };
}

/**
 * Install the synthetic reception API on one page and supply the write CSRF
 * cookie the real transport requires. Call before navigating to the harness.
 *
 * @param page page that owns the browser context to mock
 * @param baseURL configured browser base URL, used to scope the CSRF cookie
 * @returns mutable state the test inspects to prove commands reached the API
 */
export async function installSyntheticReceptionApi(
  page: Page,
  baseURL: string | undefined,
): Promise<SyntheticReceptionApi> {
  const api: SyntheticReceptionApi = {
    calls: [],
    patients: [],
    appointments: [{ ...seedAppointment }],
  };
  let patientSequence = 0;
  let appointmentSequence = 0;

  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'synthetic-csrf-token',
      url: baseURL ?? 'http://127.0.0.1:5173',
    },
  ]);

  await page.route('**/api/v1/patients*', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    api.calls.push(`${method} ${path}`);

    if (method === 'GET' && path === '/api/v1/patients') {
      await json(route, api.patients);
      return;
    }

    if (method === 'POST' && path === '/api/v1/patients') {
      const body = request.postDataJSON() as Record<string, unknown>;
      const displayName = text(body, 'displayName', 'Synthetic New Patient');
      patientSequence += 1;
      const patient: SyntheticPatient = {
        id: `patient-syn-${patientSequence}`,
        version: 1,
        mrn: text(body, 'mrn', 'MRN-9001'),
        mrnIssuer: text(body, 'issuer', 'SYNTHETIC'),
        displayName,
        normalizedName: displayName.toLowerCase(),
        dob: text(body, 'dob', '') === '' ? null : text(body, 'dob', ''),
        dobUnknownReason: null,
        language: text(body, 'language', 'en'),
        contacts: '{}',
        identifiers: '[]',
        consents: '[]',
      };
      api.patients.push(patient);
      await json(route, patient);
      return;
    }

    await json(route, { code: 'NOT_FOUND' }, 404);
  });

  // Two patterns: `*` covers the query string on the list GET and the bare
  // book POST, while `**` covers the nested check-in path.
  const handleAppointments = async (route: Route): Promise<void> => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    api.calls.push(`${method} ${path}`);

    if (method === 'GET' && path === '/api/v1/appointments') {
      await json(route, { items: api.appointments.map(asView), nextCursor: null });
      return;
    }

    if (method === 'POST' && path === '/api/v1/appointments') {
      const body = request.postDataJSON() as Record<string, unknown>;
      appointmentSequence += 1;
      const appointment: SyntheticAppointment = {
        id: `apt-syn-${appointmentSequence}`,
        version: 1,
        patientId: text(body, 'patientId', 'patient-book'),
        facilityCode: text(body, 'facilityCode', 'MAIN'),
        clinicianCode: text(body, 'clinicianCode', 'clinician-7'),
        roomCode: null,
        visitType: text(body, 'visitType', 'Synthetic follow-up'),
        startsAt: text(body, 'startsAt', '2026-03-09T07:00:00Z'),
        endsAt: '2026-03-09T07:30:00Z',
        state: 'booked',
        seriesId: null,
        expiresAt: null,
        overrideReason: null,
      };
      api.appointments.push(appointment);
      await json(route, asView(appointment));
      return;
    }

    const checkIn = /^\/api\/v1\/appointments\/([^/]+)\/check-in$/.exec(path);
    if (method === 'POST' && checkIn !== null) {
      const id = decodeURIComponent(checkIn[1] ?? '');
      const appointment = api.appointments.find((item) => item.id === id);
      if (appointment === undefined) {
        await json(route, { code: 'NOT_FOUND' }, 404);
        return;
      }
      appointment.state = 'checked_in';
      appointment.version += 1;
      await json(route, asView(appointment));
      return;
    }

    await json(route, { code: 'NOT_FOUND' }, 404);
  };

  await page.route('**/api/v1/appointments*', handleAppointments);
  await page.route('**/api/v1/appointments/**', handleAppointments);

  return api;
}
