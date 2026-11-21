import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { send } from '../../api/transport';
import type {
  AccessionBody,
  AccessionListQuery,
  AccessionPage,
  AccessionView,
  CollectBody,
  LabAccession,
  RecollectBody,
  ReceiveBody,
  RejectBody,
} from './types';

/** The one contract path declared in the generated client. */
const BASE_PATH = '/api/v1/lab/accessions';

/**
 * Build a POST descriptor for a command sub-path. The sub-paths are not yet in
 * the generated contract, so only the URL is substituted; tenant, correlation,
 * version and idempotency headers stay owned by the shared request builder.
 */
function commandDescriptor(
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor('POST', BASE_PATH, meta, body);
  return { ...base, url: path };
}

/** Build a GET descriptor for a single-accession sub-path. */
function readDescriptor(path: string, meta: RequestMeta): RequestDescriptor {
  const base = buildRequestDescriptor('GET', BASE_PATH, meta);
  return { ...base, url: path };
}

/**
 * Fetch one cursor-paginated page of accessions in the active organization.
 *
 * @throws TransportError for every non-ok, offline or timed-out response.
 */
export async function listAccessions(
  query: AccessionListQuery,
  organizationId: string,
): Promise<AccessionPage> {
  const base = buildRequestDescriptor('GET', BASE_PATH, {
    activeOrganization: organizationId,
  });
  const params = new URLSearchParams();
  if (query.cursor !== undefined) {
    params.set('cursor', query.cursor);
  }
  if (query.state !== undefined) {
    params.set('state', query.state);
  }
  if (query.limit !== undefined) {
    params.set('limit', String(query.limit));
  }
  const suffix = params.toString();
  const url = suffix === '' ? base.url : `${base.url}?${suffix}`;
  return send<AccessionPage>({ ...base, url });
}

/** Read one accession with its immutable history. */
export function getAccession(id: string, organizationId: string): Promise<AccessionView> {
  return send<AccessionView>(
    readDescriptor(`${BASE_PATH}/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Accession a requisition with a fresh idempotency key. */
export function accession(body: AccessionBody, organizationId: string): Promise<LabAccession> {
  return send<LabAccession>(
    commandDescriptor(
      BASE_PATH,
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
      body,
    ),
  );
}

/** Record collection after matching patient and accession at the bedside. */
export function collect(
  id: string,
  body: CollectBody,
  organizationId: string,
): Promise<LabAccession> {
  return send<LabAccession>(
    commandDescriptor(
      `${BASE_PATH}/${encodeURIComponent(id)}/collect`,
      { activeOrganization: organizationId, ifMatch: body.expectedVersion },
      body,
    ),
  );
}

/** Record receipt and the quality check, guarded by the version. */
export function receive(
  id: string,
  body: ReceiveBody,
  organizationId: string,
): Promise<LabAccession> {
  return send<LabAccession>(
    commandDescriptor(
      `${BASE_PATH}/${encodeURIComponent(id)}/receive`,
      { activeOrganization: organizationId, ifMatch: body.expectedVersion },
      body,
    ),
  );
}

/** Reject a specimen, retaining the reason and notification. */
export function reject(
  id: string,
  body: RejectBody,
  organizationId: string,
): Promise<LabAccession> {
  return send<LabAccession>(
    commandDescriptor(
      `${BASE_PATH}/${encodeURIComponent(id)}/reject`,
      { activeOrganization: organizationId, ifMatch: body.expectedVersion },
      body,
    ),
  );
}

/** Create a linked recollection of a rejected specimen. */
export function recollect(
  id: string,
  body: RecollectBody,
  organizationId: string,
): Promise<LabAccession> {
  return send<LabAccession>(
    commandDescriptor(
      `${BASE_PATH}/${encodeURIComponent(id)}/recollect`,
      { activeOrganization: organizationId, ifMatch: body.expectedVersion },
      body,
    ),
  );
}
