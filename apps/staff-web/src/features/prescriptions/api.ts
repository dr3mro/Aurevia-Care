import { buildRequestDescriptor } from '../../api/client';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../api/client';
import { send } from '../../api/transport';
import type { DrugCandidate, DraftFields, PrescriptionEvent, TransmissionOutcome } from './domain';

/**
 * The authoritative prescription projection. Events are append-only facts and
 * `transmission` records whether the partner gave a definite answer (RX-02).
 */
export interface PrescriptionResponse {
  id: string;
  version: number;
  submissionId: string | null;
  transmission: TransmissionOutcome | null;
  events: readonly PrescriptionEvent[];
}

/**
 * Prescription command paths are not published in the generated contract yet.
 * A declared contract path seeds the shared builder so tenant, correlation,
 * idempotency and version metadata stay in one place; only the URL is replaced.
 */
const COMMAND_SEED_PATH: ApiPath = '/api/v1/appointments';

/** Build a descriptor for a prescription sub-path not yet in the contract. */
function commandDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, COMMAND_SEED_PATH, meta, body);
  return { ...base, url: path };
}

/**
 * Search the market-approved drug catalog in the active organization. An empty
 * query or formulation is a wildcard; the catalog family is the published
 * `drugs` family of `GET /api/v1/catalogs/{family}`.
 */
export async function searchDrugCatalog(
  query: string,
  formulation: string,
  organizationId: string,
): Promise<readonly DrugCandidate[]> {
  const base = buildRequestDescriptor('GET', '/api/v1/catalogs/{family}', {
    activeOrganization: organizationId,
  });
  const params = new URLSearchParams();
  if (query.length > 0) {
    params.set('q', query);
  }
  if (formulation.length > 0) {
    params.set('formulation', formulation);
  }
  const search = params.toString();
  const url = `${base.url.replace('{family}', 'drugs')}${search === '' ? '' : `?${search}`}`;
  return send<readonly DrugCandidate[]>({ ...base, url });
}

/**
 * Create, attest and route one prescription draft. A second submission of the
 * same record carries its id and version as an `If-Match` precondition, so a
 * stale draft is rejected instead of overwriting a newer server revision. The
 * idempotency key is fresh per user action and reused on retry of that action.
 */
export async function submitPrescription(
  draft: DraftFields,
  organizationId: string,
  existing?: { id: string; version: number } | undefined,
): Promise<PrescriptionResponse> {
  const meta: RequestMeta = {
    activeOrganization: organizationId,
    idempotencyKey: crypto.randomUUID(),
  };
  if (existing !== undefined) {
    meta.ifMatch = existing.version;
  }
  const path =
    existing === undefined
      ? '/api/v1/prescriptions'
      : `/api/v1/prescriptions/${encodeURIComponent(existing.id)}/submit`;
  return send<PrescriptionResponse>(commandDescriptor('POST', path, meta, { ...draft }));
}

/**
 * Read the authoritative prescription so a conflict can reload the current
 * server version without discarding the prescriber's typed work.
 */
export async function getPrescription(
  id: string,
  organizationId: string,
): Promise<PrescriptionResponse> {
  return send<PrescriptionResponse>(
    commandDescriptor('GET', `/api/v1/prescriptions/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Verify partner status for an ambiguous transmission before any retry. */
export async function reconcileTransmission(
  id: string,
  organizationId: string,
): Promise<PrescriptionResponse> {
  return send<PrescriptionResponse>(
    commandDescriptor(
      'POST',
      `/api/v1/prescriptions/${encodeURIComponent(id)}/reconcile`,
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
    ),
  );
}
