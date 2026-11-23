/**
 * Real-API client for the prescriber's pharmacy review (RX-02). Every
 * descriptor carries the active tenant and, for writes, a version precondition
 * and a fresh idempotency key. Response text never enters an error or a log:
 * failures are normalized to the shared typed transport error.
 */

import { buildRequestDescriptor } from '../../api/client';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type { AnswerDraft, DecisionDraft, ProposalViewResponse, ThreadViewResponse } from './types';

/**
 * Pharmacy routes are not published in the generated contract yet, so a real
 * contract path supplies the shared tenant, correlation, idempotency and version
 * headers while only the URL is substituted (ARC-03).
 */
const HEADER_SOURCE_PATH: ApiPath = '/api/v1/facilities';

/** Build a descriptor for one unpublished pharmacy route. */
function prescriberDescriptor(
  method: 'GET' | 'POST',
  path: string,
  meta: RequestMeta,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const base = buildRequestDescriptor(method, HEADER_SOURCE_PATH, meta, body);
  return { ...base, url: path };
}

/** Metadata shared by every decision command: version plus a new retry key. */
function commandMeta(version: number, organizationId: string): RequestMeta {
  return {
    activeOrganization: organizationId,
    ifMatch: version,
    idempotencyKey: crypto.randomUUID(),
  };
}

/** Read one clarification thread visible to its two parties. */
export async function getClarificationThread(
  id: string,
  organizationId: string,
): Promise<ThreadViewResponse> {
  return send<ThreadViewResponse>(
    prescriberDescriptor('GET', `/api/v1/pharmacy/clarifications/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Answer an open request with text, an amended revision, or both. */
export async function answerClarification(
  threadId: string,
  version: number,
  draft: AnswerDraft,
  organizationId: string,
): Promise<ThreadViewResponse> {
  return send<ThreadViewResponse>(
    prescriberDescriptor(
      'POST',
      `/api/v1/pharmacy/clarifications/${encodeURIComponent(threadId)}/answer`,
      commandMeta(version, organizationId),
      { answerBody: draft.answerBody, amendedRevisionId: draft.amendedRevisionId },
    ),
  );
}

/** Read one substitution proposal and its side-by-side catalog entries. */
export async function getSubstitutionProposal(
  id: string,
  organizationId: string,
): Promise<ProposalViewResponse> {
  return send<ProposalViewResponse>(
    prescriberDescriptor('GET', `/api/v1/pharmacy/substitutions/${encodeURIComponent(id)}`, {
      activeOrganization: organizationId,
    }),
  );
}

/** Approve a proposal with an explicit attestation, creating its signed revision. */
export async function approveSubstitution(
  proposalId: string,
  version: number,
  draft: DecisionDraft,
  organizationId: string,
): Promise<ProposalViewResponse> {
  return send<ProposalViewResponse>(
    prescriberDescriptor(
      'POST',
      `/api/v1/pharmacy/substitutions/${encodeURIComponent(proposalId)}/approve`,
      commandMeta(version, organizationId),
      { attestation: draft.attestation, note: draft.note },
    ),
  );
}

/** Decline a proposal, leaving the originally prescribed medication untouched. */
export async function declineSubstitution(
  proposalId: string,
  version: number,
  note: string,
  organizationId: string,
): Promise<ProposalViewResponse> {
  return send<ProposalViewResponse>(
    prescriberDescriptor(
      'POST',
      `/api/v1/pharmacy/substitutions/${encodeURIComponent(proposalId)}/decline`,
      commandMeta(version, organizationId),
      { attestation: '', note },
    ),
  );
}

/** Normalize an unexpected rejection to the typed error the state views show. */
export function asPrescriberTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}
