/**
 * Real-API client for patient search and registration (PRD-01, IAM-03, IAM-04).
 * Every call carries the active organization through the shared request builder.
 * Response text is never copied into an error or a log.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type { PatientResponse, PatientViewResponse, RegistrationDraft } from './domain';
import { registrationBody } from './domain';

/**
 * Patient routes are not published in the generated contract yet, so this
 * module borrows a real contract path for the shared tenant/correlation/
 * idempotency headers and substitutes only the URL.
 */
const HEADER_SOURCE_PATH: ApiPath = '/api/v1/facilities';

/** Build a descriptor for an unpublished versioned patient route. */
function patientDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, HEADER_SOURCE_PATH, meta, body);
  return { ...base, url: path };
}

/**
 * Search patients by display name in the active organization.
 *
 * @throws TransportError for every non-ok, offline or timed-out response.
 */
export async function searchPatients(
  name: string,
  organizationId: string,
): Promise<readonly PatientResponse[]> {
  return send<readonly PatientResponse[]>(
    patientDescriptor('GET', `/api/v1/patients?name=${encodeURIComponent(name)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Register one patient with a fresh idempotency key per user action. */
export async function registerPatient(
  draft: RegistrationDraft,
  organizationId: string,
): Promise<PatientResponse> {
  return send<PatientResponse>(
    patientDescriptor(
      'POST',
      '/api/v1/patients',
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
      registrationBody(draft),
    ),
  );
}

/** Read one effective patient together with its likely-duplicate warnings. */
export async function getPatient(id: string, organizationId: string): Promise<PatientViewResponse> {
  return send<PatientViewResponse>(
    patientDescriptor('GET', `/api/v1/patients/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Normalize an unexpected rejection to the typed error the state views show. */
export function asPatientsTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}
