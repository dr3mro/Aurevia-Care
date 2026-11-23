import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor, RequestMeta } from '../../api/client';
import { send } from '../../api/transport';
import type {
  ImagingWorklistOrder,
  Modality,
  StatusFilter,
  Urgency,
  WorklistStatus,
} from './domain';

/** Uppercase wire names of the worklist status enum. */
export type ApiStatus = 'RECEIVED' | 'SCHEDULED' | 'PERFORMED' | 'REPORTED';

/** Uppercase wire names of the approved modality enum. */
export type ApiModality = 'CT' | 'MRI' | 'XRAY' | 'ULTRASOUND' | 'PET';

/** Uppercase wire names of the urgency enum. */
export type ApiUrgency = 'ROUTINE' | 'URGENT' | 'STAT';

/** One row of `GET /api/v1/radiology/worklist`. */
export interface WorklistEntryResponse {
  orderId: string;
  performingSiteId: string;
  patientId: string;
  orderCode: string;
  urgency: ApiUrgency;
  status: ApiStatus;
  version: number;
  scheduledAt: string | null;
  modality: ApiModality | null;
  accession: string | null;
  accessionIssuer: string | null;
  studyInstanceUid: string | null;
}

/** Body of a scheduling command; the actor and tenant stay server-owned. */
export interface ScheduleBody {
  expectedVersion: number;
  performingSiteId: string;
  scheduledAt: string;
  modality: ApiModality;
  accession: string;
  accessionIssuer: string;
}

/** Body of a verified study-link command. */
export interface StudyLinkBody {
  expectedVersion: number;
  performingSiteId: string;
  studyInstanceUid: string;
  patientId: string;
  accession: string;
  workstationRef: string;
}

/** Body of the performed-study handoff command. */
export interface HandoffBody {
  expectedVersion: number;
  performingSiteId: string;
}

/** The small projection a command returns to the caller. */
export interface WorklistCommandResponse {
  orderId: string;
  status: ApiStatus;
  version: number;
}

/**
 * Build a command descriptor. The worklist sub-paths are not published in the
 * generated contract yet, so only the URL is substituted; tenant, correlation,
 * version and idempotency headers stay owned by the shared request builder.
 */
function commandDescriptor(
  path: string,
  meta: RequestMeta,
  body: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor('POST', '/api/v1/appointments', meta, body);
  return { ...base, url: path };
}

/**
 * Fetch the current worklist of one performing site in the active tenant.
 *
 * @throws TransportError for every non-ok, offline or timed-out response.
 */
export async function listWorklist(
  site: string,
  filter: StatusFilter,
  organizationId: string,
): Promise<WorklistEntryResponse[]> {
  const base = buildRequestDescriptor('GET', '/api/v1/appointments', {
    activeOrganization: organizationId,
  });
  const params = new URLSearchParams({ site });
  if (filter !== 'all') {
    params.set('status', filter.toUpperCase());
  }
  return send<WorklistEntryResponse[]>({ ...base, url: `${base.url}?${params.toString()}` });
}

/** Record the study time, modality and accession on a received order. */
export async function scheduleOrder(
  orderId: string,
  body: ScheduleBody,
  organizationId: string,
): Promise<WorklistCommandResponse> {
  return send<WorklistCommandResponse>(
    commandDescriptor(
      `/api/v1/radiology/worklist/orders/${encodeURIComponent(orderId)}/schedule`,
      {
        activeOrganization: organizationId,
        ifMatch: body.expectedVersion,
        idempotencyKey: crypto.randomUUID(),
      },
      { ...body },
    ),
  );
}

/** Link an external study after the patient and accession labels match. */
export async function linkStudy(
  orderId: string,
  body: StudyLinkBody,
  organizationId: string,
): Promise<WorklistCommandResponse> {
  return send<WorklistCommandResponse>(
    commandDescriptor(
      `/api/v1/radiology/worklist/orders/${encodeURIComponent(orderId)}/studies`,
      {
        activeOrganization: organizationId,
        ifMatch: body.expectedVersion,
        idempotencyKey: crypto.randomUUID(),
      },
      { ...body },
    ),
  );
}

/** Hand a performed study off to the reporting step. */
export async function reportOrder(
  orderId: string,
  body: HandoffBody,
  organizationId: string,
): Promise<WorklistCommandResponse> {
  return send<WorklistCommandResponse>(
    commandDescriptor(
      `/api/v1/radiology/worklist/orders/${encodeURIComponent(orderId)}/report`,
      {
        activeOrganization: organizationId,
        ifMatch: body.expectedVersion,
        idempotencyKey: crypto.randomUUID(),
      },
      { ...body },
    ),
  );
}

/**
 * Project one API row onto the display type, bound to the active tenant. The
 * response has no display name or facility zone, so the opaque patient id is
 * the identity shown on the row and instants are labelled UTC.
 */
export function toOrder(
  entry: WorklistEntryResponse,
  organizationId: string,
): ImagingWorklistOrder {
  return {
    organizationId,
    orderId: entry.orderId,
    performingSiteId: entry.performingSiteId,
    patientId: entry.patientId,
    orderCode: entry.orderCode,
    urgency: entry.urgency.toLowerCase() as Urgency,
    status: entry.status.toLowerCase() as WorklistStatus,
    version: entry.version,
    scheduledAt: entry.scheduledAt,
    modality: entry.modality === null ? null : (entry.modality.toLowerCase() as Modality),
    accession: entry.accession,
    accessionIssuer: entry.accessionIssuer,
    studyInstanceUid: entry.studyInstanceUid,
  };
}
