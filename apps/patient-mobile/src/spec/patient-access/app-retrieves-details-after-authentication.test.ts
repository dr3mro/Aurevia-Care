/**
 * Patient-access clause proof: the app retrieves details only after
 * authentication (PAT-01 clause 3). All fixtures are synthetic projections and
 * the token store is replaced with an OS-store-shaped in-memory mock; no real
 * credential, host or patient record is used. The positive test proves a valid
 * session can read one scoped detail view; the negative tests prove the same
 * read is refused when the credential is absent/blank or the server rejects it.
 */
import { jest } from '@jest/globals';

import { loadToken } from '../../api/tokenStore';
import {
  TransportError,
  type HttpResponse,
  type TransportErrorKind,
} from '../../api/transport';
import { PATIENT_API_BASE_URL, createPatientTransport, loadAppointments } from '../../app/providers';

jest.mock('../../api/tokenStore', () => ({
  loadToken: jest.fn(async () => 'synthetic-session-token'),
  saveToken: jest.fn(async () => undefined),
  clearToken: jest.fn(async () => undefined),
}));

const organizationId = 'synthetic-organization';
const patientId = 'synthetic-patient';
const sessionToken = 'synthetic-session-token';
const detailUrl = `${PATIENT_API_BASE_URL}/api/v1/patient/appointments?patientId=${patientId}`;

/** One synthetic appointment detail projection returned by the patient endpoint. */
const appointment = {
  id: 'apt-1',
  version: 3,
  patientId,
  facilityCode: 'synthetic-facility',
  facilityTimeZone: 'Asia/Riyadh',
  startsAt: '2026-11-21T10:00:00Z',
  status: 'booked',
  confirmationEnabled: true,
  confirmedAt: null,
};

/** Build a minimal HTTP response for the injected synthetic fetch seam. */
function jsonResponse(status: number, body: unknown): HttpResponse {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => null },
    json: async () => body,
  };
}

/** Capture a rejection as a value so both its class and kind can be asserted. */
async function capturedError(promise: Promise<unknown>): Promise<unknown> {
  return promise.then(
    () => undefined,
    (error: unknown) => error,
  );
}

/** Assert a rejection is a typed transport refusal of the expected kind. */
function expectTypedRefusal(error: unknown, kind: TransportErrorKind): void {
  expect(error).toBeInstanceOf(TransportError);
  expect(error).toMatchObject({ kind });
}

const loadTokenMock = jest.mocked(loadToken);

beforeEach(() => {
  loadTokenMock.mockResolvedValue(sessionToken);
});

describe('retrieving patient details after authentication', () => {
  it('retrievesPatientDetailsAfterAuthentication', async () => {
    const fetchImpl = jest.fn(async (_url: string, _init: RequestInit) =>
      jsonResponse(200, [appointment]));
    const transport = createPatientTransport(PATIENT_API_BASE_URL, 'en', fetchImpl);

    const rows = await loadAppointments(transport, patientId, organizationId);

    expect(rows).toHaveLength(1);
    expect(rows[0]!.key).toBe(`${organizationId}\u0000apt-1`);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe(detailUrl);
    expect((init.headers as Record<string, string>)['Authorization']).toBe(
      `Bearer ${sessionToken}`,
    );
  });

  it('refusesToRetrieveDetailsWithoutACredential', async () => {
    for (const token of [null, '   ']) {
      loadTokenMock.mockResolvedValue(token);
      const fetchImpl = jest.fn(async () => jsonResponse(200, [appointment]));
      const transport = createPatientTransport(PATIENT_API_BASE_URL, 'en', fetchImpl);

      const error = await capturedError(loadAppointments(transport, patientId, organizationId));
      expectTypedRefusal(error, 'unauthenticated');
      // No credential means no detail request may leave the device at all.
      expect(fetchImpl).not.toHaveBeenCalled();
    }
  });

  it('refusesDetailsWhenServerReportsForbidden', async () => {
    const fetchImpl = jest.fn(async (_url: string, _init: RequestInit) =>
      jsonResponse(403, { code: 'FORBIDDEN' }));
    const transport = createPatientTransport(PATIENT_API_BASE_URL, 'en', fetchImpl);

    const error = await capturedError(loadAppointments(transport, patientId, organizationId));
    expectTypedRefusal(error, 'forbidden');
    // A valid session did attempt the scoped read; the server refused it and no detail came back.
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl.mock.calls[0]![0]).toBe(detailUrl);
  });

  it('mapsAServerUnauthorizedResponseToUnauthenticated', async () => {
    const fetchImpl = jest.fn(async (_url: string, _init: RequestInit) =>
      jsonResponse(401, { code: 'UNAUTHENTICATED' }));
    const transport = createPatientTransport(PATIENT_API_BASE_URL, 'en', fetchImpl);

    const error = await capturedError(loadAppointments(transport, patientId, organizationId));
    expectTypedRefusal(error, 'unauthenticated');
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl.mock.calls[0]![0]).toBe(detailUrl);
  });
});
