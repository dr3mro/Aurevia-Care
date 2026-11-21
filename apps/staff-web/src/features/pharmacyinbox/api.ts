/**
 * Real-API client for the pharmacy inbox and its four status commands (RX-02).
 * Every descriptor carries the active tenant and, for writes, a version
 * precondition and a fresh idempotency key. Response text never enters an error
 * or a log: failures are normalized to the shared typed transport error.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import { filterQuery } from './domain';
import type {
  DispensingEvidence,
  InboxItemResponse,
  InboxPageResponse,
  StatusFilter,
  StatusEventResponse,
  StatusViewResponse,
} from './types';

/**
 * Pharmacy routes are not published in the generated contract yet, so a real
 * contract path supplies the shared tenant, correlation, idempotency and version
 * headers while only the URL is substituted (ARC-03).
 */
const HEADER_SOURCE_PATH: ApiPath = '/api/v1/facilities';

/** Build a descriptor for one unpublished pharmacy route. */
function pharmacyDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, HEADER_SOURCE_PATH, meta, body);
  return { ...base, url: path };
}

/** Metadata shared by every status command: version plus a new retry key. */
function commandMeta(version: number, organizationId: string): RequestMeta {
  return {
    activeOrganization: organizationId,
    ifMatch: version,
    idempotencyKey: crypto.randomUUID(),
  };
}

/**
 * List one cursor-paginated page of prescriptions routed to the active
 * pharmacy, optionally narrowed to a single prescription state.
 */
export async function listPrescriptions(
  filter: StatusFilter,
  cursor: string | undefined,
  organizationId: string,
): Promise<InboxPageResponse> {
  const status = filterQuery(filter);
  const params = new URLSearchParams();
  if (status !== undefined) {
    params.set('status', status);
  }
  if (cursor !== undefined) {
    params.set('cursor', cursor);
  }
  const base = pharmacyDescriptor('GET', '/api/v1/pharmacy/inbox', {
    activeOrganization: organizationId,
  });
  return send<InboxPageResponse>({ ...base, url: `${base.url}?${params.toString()}` });
}

/** Read one prescription explicitly routed to the active pharmacy. */
export async function getPrescription(
  id: string,
  organizationId: string,
): Promise<InboxItemResponse> {
  return send<InboxItemResponse>(
    pharmacyDescriptor('GET', `/api/v1/pharmacy/inbox/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Read the current status projection and its fact trail. */
export async function getPharmacyStatus(
  id: string,
  organizationId: string,
): Promise<StatusViewResponse> {
  return send<StatusViewResponse>(
    pharmacyDescriptor('GET', `/api/v1/pharmacy/status/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Append an acceptance fact for the expected status version. */
export async function acceptPrescription(
  id: string,
  version: number,
  organizationId: string,
): Promise<StatusEventResponse> {
  return send<StatusEventResponse>(
    pharmacyDescriptor(
      'POST',
      `/api/v1/pharmacy/status/${encodeURIComponent(id)}/accept`,
      commandMeta(version, organizationId),
    ),
  );
}

/** Append a rejection fact with the mandatory reason. */
export async function rejectPrescription(
  id: string,
  version: number,
  reason: string,
  organizationId: string,
): Promise<StatusEventResponse> {
  return send<StatusEventResponse>(
    pharmacyDescriptor(
      'POST',
      `/api/v1/pharmacy/status/${encodeURIComponent(id)}/reject`,
      commandMeta(version, organizationId),
      { reason },
    ),
  );
}

/** Append a clarification request to the prescriber with the question. */
export async function askClarification(
  id: string,
  version: number,
  question: string,
  organizationId: string,
): Promise<StatusEventResponse> {
  return send<StatusEventResponse>(
    pharmacyDescriptor(
      'POST',
      `/api/v1/pharmacy/status/${encodeURIComponent(id)}/clarification`,
      commandMeta(version, organizationId),
      { question },
    ),
  );
}

/**
 * Append dispensing evidence. This is a distinct event and never sets
 * acceptance; the domain service enforces that separation authoritatively.
 */
export async function recordDispensing(
  id: string,
  version: number,
  evidence: DispensingEvidence,
  organizationId: string,
): Promise<StatusEventResponse> {
  return send<StatusEventResponse>(
    pharmacyDescriptor(
      'POST',
      `/api/v1/pharmacy/status/${encodeURIComponent(id)}/dispensing-evidence`,
      commandMeta(version, organizationId),
      {
        dispensedBy: evidence.dispensedBy,
        dispensedAt: evidence.dispensedAt,
        handedOver: evidence.handedOver,
      },
    ),
  );
}

/** Normalize an unexpected rejection to the typed error the state views show. */
export function asPharmacyTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}
