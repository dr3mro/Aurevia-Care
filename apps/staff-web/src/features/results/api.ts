/**
 * Real-API client for XCH-04 result reconciliation and XCH-05 critical results.
 *
 * Every call carries the active organization through the shared request builder
 * and returns a typed projection from `domain.ts`. The generated contract client
 * does not yet declare the exchange paths, so a declared path is used for header
 * construction and the request URL is then set to the authoritative endpoint.
 * No response text is ever copied into an error or a log (WEB-02, WEB-06).
 */

import { buildRequestDescriptor } from '../../api/client';
import type { ApiPath, RequestDescriptor, RequestMeta } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type {
  CriticalReport,
  CriticalView,
  ReconciliationEntry,
  ReconciliationStatus,
  ResultDetail,
  Review,
} from './domain';

/** A declared path used only to build tenant and precondition headers. */
const HEADER_PATH: ApiPath = '/api/v1/facilities';

/** Build a tenant-scoped GET descriptor, adding only supplied query values. */
function getDescriptor(
  path: string,
  organizationId: string,
  query?: Readonly<Record<string, string>>,
): RequestDescriptor {
  const base = buildRequestDescriptor('GET', HEADER_PATH, {
    activeOrganization: organizationId,
  });
  if (query === undefined) {
    return { ...base, url: path };
  }
  const suffix = new URLSearchParams(query).toString();
  return { ...base, url: `${path}?${suffix}` };
}

/** Build a tenant-scoped command descriptor bound to one observed version. */
function commandDescriptor(
  path: string,
  organizationId: string,
  version: number,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const meta: RequestMeta = { activeOrganization: organizationId, ifMatch: version };
  const base = buildRequestDescriptor('POST', HEADER_PATH, meta, body);
  return { ...base, url: path };
}

/** Normalize an unexpected rejection to the typed error the state views show. */
export function asResultTransportError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** List a bounded page of reconciliation entries for one queue status. */
export async function listReconciliations(
  status: ReconciliationStatus,
  organizationId: string,
): Promise<ReconciliationEntry[]> {
  return send<ReconciliationEntry[]>(
    getDescriptor('/api/v1/xch04/reconciliations', organizationId, { status }),
  );
}

/** Read one immutable result revision with its review, lineage and notices. */
export async function getResult(id: string, organizationId: string): Promise<ResultDetail> {
  return send<ResultDetail>(
    getDescriptor(`/api/v1/xch04/results/${encodeURIComponent(id)}`, organizationId),
  );
}

/** Acknowledge the exact revision named by the caller's version precondition. */
export async function acknowledgeResult(
  id: string,
  version: number,
  organizationId: string,
): Promise<Review> {
  return send<Review>(
    commandDescriptor(
      `/api/v1/xch04/results/${encodeURIComponent(id)}/acknowledge`,
      organizationId,
      version,
    ),
  );
}

/** Read the operational report of overdue alerts, roster gaps and failures. */
export async function getCriticalReport(organizationId: string): Promise<CriticalReport> {
  return send<CriticalReport>(getDescriptor('/api/v1/critical-results/report', organizationId));
}

/** Read one critical result and its immutable event trail. */
export async function getCriticalResult(id: string, organizationId: string): Promise<CriticalView> {
  return send<CriticalView>(
    getDescriptor(`/api/v1/critical-results/${encodeURIComponent(id)}`, organizationId),
  );
}

/** Record a clinician's acknowledgment of the exact critical revision. */
export async function acknowledgeCritical(
  id: string,
  version: number,
  note: string,
  organizationId: string,
): Promise<CriticalView['result']> {
  return send<CriticalView['result']>(
    commandDescriptor(
      `/api/v1/critical-results/${encodeURIComponent(id)}/acknowledge`,
      organizationId,
      version,
      { note },
    ),
  );
}

/** Assign a responsible clinician and a future acknowledgment deadline. */
export async function assignCritical(
  id: string,
  version: number,
  ownerSubject: string,
  dueAt: string,
  organizationId: string,
): Promise<CriticalView['result']> {
  return send<CriticalView['result']>(
    commandDescriptor(
      `/api/v1/critical-results/${encodeURIComponent(id)}/assign`,
      organizationId,
      version,
      { ownerSubject, dueAt },
    ),
  );
}

/**
 * Record one escalation attempt. The UI's required reason is carried in the
 * non-sensitive `detail` field; channel, recipient and outcome are chosen by
 * the operator and validated against site policy by the server.
 */
export async function escalateCritical(
  id: string,
  version: number,
  reason: string,
  channel: string,
  recipient: string,
  outcome: 'delivered' | 'failed',
  systemDown: boolean,
  organizationId: string,
): Promise<CriticalView['result']> {
  return send<CriticalView['result']>(
    commandDescriptor(
      `/api/v1/critical-results/${encodeURIComponent(id)}/escalate`,
      organizationId,
      version,
      { channel, recipient, outcome, detail: reason, systemDown },
    ),
  );
}

/** Record the verbatim value repeated by the recipient where policy requires it. */
export async function readBackCritical(
  id: string,
  version: number,
  repeatedValue: string,
  organizationId: string,
): Promise<CriticalView['result']> {
  return send<CriticalView['result']>(
    commandDescriptor(
      `/api/v1/critical-results/${encodeURIComponent(id)}/read-back`,
      organizationId,
      version,
      { repeatedValue },
    ),
  );
}
