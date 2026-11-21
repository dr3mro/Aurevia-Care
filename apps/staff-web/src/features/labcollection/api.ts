import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { send } from '../../api/transport';
import type { AccessionState, CollectionOrder, LabelData } from './domain';

/** The authoritative wire states of one accession (LAB-01). */
export type ApiAccessionState = AccessionState;

/** The accession projection returned by `GET /api/v1/lab/accessions`. */
export interface LabAccessionResponse {
  id: string;
  version: number;
  accessionNo: string;
  accessionIssuer: string;
  patientId: string;
  sourceOrderRef: string;
  state: ApiAccessionState;
  specimenType: string | null;
  siteCode: string | null;
  collector: string | null;
  collectedAt: string | null;
  receivedAt: string | null;
  quality: string | null;
  rejectionReason: string | null;
  rejectionNotification: string | null;
  replacesAccessionId: string | null;
}

/** One accession and its append-only history as returned by the API. */
export interface AccessionViewResponse {
  accession: LabAccessionResponse;
  events: readonly unknown[];
}

/** One cursor-paginated page of accession views. */
export interface AccessionPageResponse {
  items: readonly AccessionViewResponse[];
  nextCursor: string | null;
}

/** Query supported by `GET /api/v1/lab/accessions`. */
export interface AccessionListQuery {
  cursor?: string | undefined;
  state?: ApiAccessionState | undefined;
  limit?: number | undefined;
}

/** Body of `POST /api/v1/lab/specimens/{id}/collect` (LABFLOW-01). */
export interface CollectBody {
  patientId: string;
  accessionNo: string;
  accessionIssuer: string;
  specimenType: string;
  siteCode: string;
  collector: string;
  collectedAt: string;
  quality: string;
  expectedVersion: number;
}

/** Body of `POST /api/v1/lab/specimens/{id}/reject` (LABFLOW-02). */
export interface RejectBody {
  reason: string;
  detail: string;
  orderingClinician: string;
  notifyPatient: boolean;
  expectedVersion: number;
}

/**
 * Build a descriptor for a lab command. Command sub-paths are not all published
 * in the generated contract yet, so only the URL is substituted; tenant,
 * correlation, version and idempotency headers stay owned by the shared builder.
 */
function commandDescriptor(
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor('POST', '/api/v1/lab/accessions', meta, body);
  return { ...base, url: path };
}

/**
 * Fetch one page of accessions in the active organization.
 *
 * @throws TransportError for every non-ok, offline or timed-out response.
 */
export async function listAccessions(
  query: AccessionListQuery,
  organizationId: string,
): Promise<AccessionPageResponse> {
  const base = buildRequestDescriptor('GET', '/api/v1/lab/accessions', {
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
  return send<AccessionPageResponse>({
    ...base,
    url: suffix === '' ? base.url : `${base.url}?${suffix}`,
  });
}

/** Record a specimen collection after the bedside identity check (LABFLOW-01). */
export async function collectSpecimen(
  id: string,
  body: CollectBody,
  organizationId: string,
): Promise<unknown> {
  return send(
    commandDescriptor(
      `/api/v1/lab/specimens/${encodeURIComponent(id)}/collect`,
      {
        activeOrganization: organizationId,
        idempotencyKey: crypto.randomUUID(),
        ifMatch: body.expectedVersion,
      },
      body as unknown as Record<string, unknown>,
    ),
  );
}

/** Read print-ready label data for a collected specimen (LABFLOW-01). */
export async function readLabel(id: string, organizationId: string): Promise<LabelData> {
  const base = buildRequestDescriptor('GET', '/api/v1/lab/accessions', {
    activeOrganization: organizationId,
  });
  return send<LabelData>({
    ...base,
    url: `/api/v1/lab/specimens/${encodeURIComponent(id)}/label`,
  });
}

/** Reject a specimen with a bounded reason and its detail (LABFLOW-02). */
export async function rejectSpecimen(
  id: string,
  body: RejectBody,
  organizationId: string,
): Promise<unknown> {
  return send(
    commandDescriptor(
      `/api/v1/lab/specimens/${encodeURIComponent(id)}/reject`,
      {
        activeOrganization: organizationId,
        idempotencyKey: crypto.randomUUID(),
        ifMatch: body.expectedVersion,
      },
      body as unknown as Record<string, unknown>,
    ),
  );
}

/**
 * Convert one API accession view into the display type. The list projection has
 * no patient name, so the opaque patient id is shown as the name until a richer
 * projection is published; identifiers remain mono and isolated in the screen.
 */
export function toCollectionOrder(
  view: AccessionViewResponse,
  organizationId: string,
): CollectionOrder {
  const accession = view.accession;
  return {
    organizationId,
    id: accession.id,
    accessionNo: accession.accessionNo,
    accessionIssuer: accession.accessionIssuer,
    patientId: accession.patientId,
    patientName: { latinName: accession.patientId },
    patientMrn: accession.patientId,
    patientMrnIssuer: accession.accessionIssuer,
    sourceOrderRef: accession.sourceOrderRef,
    state: accession.state,
    version: accession.version,
  };
}
