/**
 * Real-API client for same-tenant duplicate review and consolidation (IAM-04).
 * Every call carries the active organization through the shared request builder.
 * Response text is never copied into an error or a log.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type {
  ApprovalRequestBody,
  ConsolidationWire,
  DuplicatePairWire,
  OpenRequestBody,
  ReviewDetailWire,
  ReviewWire,
} from './domain';

/**
 * Duplicate routes are not published in the generated contract yet, so this
 * module borrows a real contract path for the shared tenant/correlation/
 * idempotency/version headers and substitutes only the URL.
 */
const HEADER_SOURCE_PATH: ApiPath = '/api/v1/facilities';

/** Build a descriptor for an unpublished versioned duplicate route. */
function duplicatesDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, HEADER_SOURCE_PATH, meta, body);
  return { ...base, url: path };
}

/** List the same-tenant likely-duplicate pairs for review. */
export async function listDuplicateReviews(
  organizationId: string,
): Promise<readonly DuplicatePairWire[]> {
  return send<readonly DuplicatePairWire[]>(
    duplicatesDescriptor('GET', '/api/v1/duplicate-reviews', {
      activeOrganization: organizationId,
    }),
  );
}

/** Read both identities, conflicts, dependencies and the dry run without mutating. */
export async function getReviewDetail(
  survivorId: string,
  duplicateId: string,
  organizationId: string,
): Promise<ReviewDetailWire> {
  return send<ReviewDetailWire>(
    duplicatesDescriptor(
      'GET',
      `/api/v1/duplicate-reviews/${encodeURIComponent(survivorId)}/${encodeURIComponent(duplicateId)}`,
      { activeOrganization: organizationId },
    ),
  );
}

/** Open a review ticket owned by the authenticated first reviewer. */
export async function openReview(
  request: OpenRequestBody,
  organizationId: string,
): Promise<ReviewWire> {
  const body: Record<string, unknown> = {
    survivorId: request.survivorId,
    duplicateId: request.duplicateId,
  };
  return send<ReviewWire>(
    duplicatesDescriptor(
      'POST',
      '/api/v1/duplicate-reviews',
      { activeOrganization: organizationId, idempotencyKey: crypto.randomUUID() },
      body,
    ),
  );
}

/**
 * Approve consolidation as a second reviewer. The `If-Match` version is the
 * duplicate patient's current version, not the review ticket version.
 */
export async function approveConsolidation(
  reviewId: string,
  duplicateVersion: number,
  approval: ApprovalRequestBody,
  organizationId: string,
): Promise<ConsolidationWire> {
  return send<ConsolidationWire>(
    duplicatesDescriptor(
      'POST',
      `/api/v1/duplicate-reviews/${encodeURIComponent(reviewId)}/consolidate`,
      {
        activeOrganization: organizationId,
        ifMatch: duplicateVersion,
        idempotencyKey: crypto.randomUUID(),
      },
      {
        reason: approval.reason,
        confirmedIdentifiers: [...approval.confirmedIdentifiers],
        alternateProcess: approval.alternateProcess,
      },
    ),
  );
}

/** Normalize an unexpected rejection to the typed error the state views show. */
export function asDuplicatesTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}
