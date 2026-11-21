/**
 * Real-API client for the radiography screens (RAD-01).
 *
 * The imaging routes are not published in the generated contract yet, so this
 * module borrows a real contract path for the shared tenant, correlation,
 * version and idempotency headers and substitutes only the URL. Response text
 * is never copied into an error or a log.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type {
  Addendum,
  ImagingOrder,
  ImagingOrderState,
  Interpretation,
  InterpretationInput,
  Modality,
  ScheduleInput,
  StudyLink,
  Urgency,
} from './domain';

/** Contract path borrowed for its shared request headers. */
const HEADER_SOURCE_PATH: ApiPath = '/api/v1/orders';

/** The wire states an imaging order can carry. */
export type ApiOrderState =
  | 'received'
  | 'scheduled'
  | 'study_linked'
  | 'interpreted'
  | 'addended';

/** A linked addendum as returned by the API. */
export interface AddendumResponse {
  id: string;
  text: string;
  author: string;
  addedAt: string;
}

/** A signed interpretation as returned by the API. */
export interface InterpretationResponse {
  technique: string;
  comparison: string;
  findings: string;
  impression: string;
  followUp: string;
  signedBy: string;
  signedAt: string;
  addenda: readonly AddendumResponse[];
}

/** One imaging order projection returned by the API. */
export interface ImagingOrderResponse {
  id: string;
  version: number;
  patientId: string;
  patientDisplayName: string;
  patientLocalName: string | null;
  urgency: Urgency;
  clinicalQuestion: string;
  state: ApiOrderState;
  accessionNo: string | null;
  modality: Modality | null;
  scheduledAt: string | null;
  studyInstanceUid: string | null;
  interpretation: InterpretationResponse | null;
}

/** One cursor-paginated page of imaging orders. */
export interface ImagingOrderPageResponse {
  items: readonly ImagingOrderResponse[];
  nextCursor: string | null;
}

/** Query supported by the imaging order list. */
export interface ImagingOrderListQuery {
  status?: string | undefined;
  cursor?: string | undefined;
  limit?: number | undefined;
}

/** Wire-to-domain state map; all wire states are handled explicitly. */
const stateMap: Record<ApiOrderState, ImagingOrderState> = {
  received: 'received',
  scheduled: 'scheduled',
  study_linked: 'study-linked',
  interpreted: 'interpreted',
  addended: 'addended',
};

/** Build a descriptor for an unpublished versioned imaging route. */
function imagingDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, HEADER_SOURCE_PATH, meta, body);
  return { ...base, url: path };
}

/** The collection path for imaging orders. */
const ORDERS_PATH = '/api/v1/imaging/orders';

/** Encode an identifier once for a versioned imaging path. */
function orderPath(id: string): string {
  return `${ORDERS_PATH}/${encodeURIComponent(id)}`;
}

/**
 * Fetch one page of imaging orders in the active organization.
 *
 * @throws TransportError for every non-ok, offline or timed-out response.
 */
export async function listImagingOrders(
  query: ImagingOrderListQuery,
  organizationId: string,
): Promise<ImagingOrderPageResponse> {
  const params = new URLSearchParams();
  if (query.status !== undefined) {
    params.set('status', query.status);
  }
  if (query.cursor !== undefined) {
    params.set('cursor', query.cursor);
  }
  if (query.limit !== undefined) {
    params.set('limit', String(query.limit));
  }
  const suffix = params.size === 0 ? '' : `?${params.toString()}`;
  return send<ImagingOrderPageResponse>(
    imagingDescriptor('GET', `${ORDERS_PATH}${suffix}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Read one imaging order with its interpretation and addenda. */
export async function getImagingOrder(
  id: string,
  organizationId: string,
): Promise<ImagingOrderResponse | null> {
  return send<ImagingOrderResponse | null>(
    imagingDescriptor('GET', orderPath(id), { activeOrganization: organizationId }),
  );
}

/** Record the accession, modality and scheduled instant. */
export async function scheduleImagingOrder(
  id: string,
  version: number,
  input: ScheduleInput,
  organizationId: string,
): Promise<ImagingOrderResponse> {
  return send<ImagingOrderResponse>(
    imagingDescriptor(
      'POST',
      `${orderPath(id)}/schedule`,
      { activeOrganization: organizationId, ifMatch: version, idempotencyKey: crypto.randomUUID() },
      {
        accessionNo: input.accessionNo,
        modality: input.modality,
        scheduledAt: input.scheduledAt,
      },
    ),
  );
}

/** Associate a verified external study with the order. */
export async function linkStudy(
  id: string,
  version: number,
  study: StudyLink,
  organizationId: string,
): Promise<ImagingOrderResponse> {
  return send<ImagingOrderResponse>(
    imagingDescriptor(
      'POST',
      `${orderPath(id)}/study`,
      { activeOrganization: organizationId, ifMatch: version, idempotencyKey: crypto.randomUUID() },
      {
        studyInstanceUid: study.studyInstanceUid,
        patientId: study.patientId,
        accessionNo: study.accessionNo,
      },
    ),
  );
}

/** Sign an interpretation with a fresh idempotency key and a version precondition. */
export async function signInterpretationReport(
  id: string,
  version: number,
  input: InterpretationInput,
  organizationId: string,
): Promise<ImagingOrderResponse> {
  return send<ImagingOrderResponse>(
    imagingDescriptor(
      'POST',
      `${orderPath(id)}/interpretation`,
      { activeOrganization: organizationId, ifMatch: version, idempotencyKey: crypto.randomUUID() },
      { ...input },
    ),
  );
}

/** Append a linked addendum to an already signed interpretation. */
export async function addAddendum(
  id: string,
  version: number,
  text: string,
  organizationId: string,
): Promise<ImagingOrderResponse> {
  return send<ImagingOrderResponse>(
    imagingDescriptor(
      'POST',
      `${orderPath(id)}/addenda`,
      { activeOrganization: organizationId, ifMatch: version, idempotencyKey: crypto.randomUUID() },
      { text },
    ),
  );
}

/** Convert one wire addendum to the domain type. */
function toAddendum(addendum: AddendumResponse): Addendum {
  return {
    id: addendum.id,
    text: addendum.text,
    author: addendum.author,
    addedAt: addendum.addedAt,
  };
}

/** Convert one wire interpretation to the domain type, preserving addenda. */
function toInterpretation(interpretation: InterpretationResponse): Interpretation {
  return {
    technique: interpretation.technique,
    comparison: interpretation.comparison,
    findings: interpretation.findings,
    impression: interpretation.impression,
    followUp: interpretation.followUp,
    signedBy: interpretation.signedBy,
    signedAt: interpretation.signedAt,
    addenda: interpretation.addenda.map(toAddendum),
  };
}

/** Project one wire order onto the display type used by the screens. */
export function toImagingOrder(response: ImagingOrderResponse): ImagingOrder {
  const localName = response.patientLocalName;
  return {
    id: response.id,
    version: response.version,
    patientId: response.patientId,
    patientName: {
      latinName: response.patientDisplayName,
      ...(localName === null ? {} : { localName }),
    },
    urgency: response.urgency,
    clinicalQuestion: response.clinicalQuestion,
    state: stateMap[response.state],
    accessionNo: response.accessionNo,
    modality: response.modality,
    scheduledAt: response.scheduledAt,
    studyInstanceUid: response.studyInstanceUid,
    interpretation:
      response.interpretation === null ? null : toInterpretation(response.interpretation),
  };
}

/** Normalize an unexpected rejection to the typed error the state views show. */
export function asRadiologyTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}
