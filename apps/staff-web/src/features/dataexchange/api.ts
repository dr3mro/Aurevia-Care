/**
 * Transport-bound API client for tenant data exchange (OPS-07, OPS-08).
 *
 * <p>The tenant export and onboarding import routes are served by the API but
 * are not yet declared in the generated contract. Following the established
 * staff-web pattern, a shared descriptor supplies the tenant, correlation,
 * idempotency and CSRF metadata and the `url` is bound to the live route. All
 * response shapes are declared locally from the server records so no response
 * text is ever copied into a log or an error message.</p>
 */

import { buildRequestDescriptor } from '../../api/client';
import type { RequestDescriptor } from '../../api/client';
import { TransportError, send } from '../../api/transport';
import type { Role } from '../../app/navigation';
import type {
  DryRunReportResponse,
  ExportResultResponse,
  SignOffResultResponse,
} from './domain';

/** A declared contract path used only to obtain the shared request headers. */
const DESCRIPTOR_BASE = '/api/v1/facilities';

/** Live route prefix for tenant export jobs. */
const EXPORT_ROUTE = '/api/v1/tenant/exports';

/** Live route prefix for onboarding import commands. */
const IMPORT_ROUTE = '/api/v1/tenant/imports';

/** Deterministic field mapping declaration sent with a synthetic manifest. */
type FieldMappingRequest = {
  deterministic: boolean;
  strategy: string;
  source_to_target: Record<string, string>;
  identity_fields: string[];
  ignored_fields: string[];
};

/** Import provenance declaration that must preserve historical signatures. */
type ImportProvenanceRequest = {
  source_system: string;
  source_version: string;
  preserve_historical_signatures: boolean;
  creates_new_attestation: boolean;
};

/** Versioned onboarding manifest sent to the dry-run endpoint. */
export type ImportManifestRequest = {
  schema_version: string;
  requirement: string;
  manifest_version: number;
  source: string;
  synthetic_only: boolean;
  records: Record<string, string>[];
  mapping: FieldMappingRequest;
  provenance: ImportProvenanceRequest;
  batches: number[];
};

/** Named authorization sent to the sign-off endpoint. */
export type ImportSignOffRequest = {
  approver: string;
  role: string;
  decision: string;
  content_sha256: string;
  manifest_version: number;
  batches: number[];
  approved_at: string;
};

/** Body of `POST /api/v1/tenant/imports/sign-off`. */
export type SignOffRequest = {
  manifest: ImportManifestRequest;
  progress: unknown;
  sign_off: ImportSignOffRequest;
};

/** Fields an operator supplies to describe a synthetic onboarding dataset. */
export interface DryRunDraft {
  source: string;
  sourceVersion: string;
  manifestVersion: number;
  /** Comma-separated source fields deliberately left unmapped by the operator. */
  unsupportedFieldNames: string;
}

/**
 * Build a fully synthetic, versioned manifest from bounded form fields.
 *
 * <p>Every record identifier is a synthetic alias and the mapping is the only
 * accepted deterministic strategy. Historical signatures are preserved and no
 * new attestation is requested, which is the OPS-08 invariant for an import.</p>
 *
 * @param draft operator-supplied dataset description
 * @returns a synthetic manifest ready for the dry-run endpoint
 */
export function manifestFromDraft(draft: DryRunDraft): ImportManifestRequest {
  const fields = draft.unsupportedFieldNames
    .split(',')
    .map((field) => field.trim())
    .filter((field) => field !== '');
  return {
    schema_version: '1.0',
    requirement: 'OPS-08',
    manifest_version: draft.manifestVersion,
    source: draft.source,
    synthetic_only: true,
    records: fields.map((field, index) => ({
      source_id: `synthetic-record-${index + 1}`,
      [field]: 'synthetic-value',
    })),
    mapping: {
      deterministic: true,
      strategy: 'canonical_sha256',
      source_to_target: {},
      identity_fields: ['source_id'],
      ignored_fields: [],
    },
    provenance: {
      source_system: draft.source,
      source_version: draft.sourceVersion,
      preserve_historical_signatures: true,
      creates_new_attestation: false,
    },
    batches: [],
  };
}

/**
 * Build the sign-off request bound to the manifest the operator just reviewed.
 *
 * @param manifest the reviewed manifest version
 * @param approver named approver supplied by the form
 * @param role active session role of the approver
 * @param contentSha256 digest of the reviewed content
 * @param approvedAt UTC instant of approval
 * @returns a sign-off body whose decision is always affirmative
 */
export function signOffRequest(
  manifest: ImportManifestRequest,
  approver: string,
  role: Role,
  contentSha256: string,
  approvedAt: string,
): SignOffRequest {
  return {
    manifest,
    progress: null,
    sign_off: {
      approver,
      role,
      decision: 'approved',
      content_sha256: contentSha256,
      manifest_version: manifest.manifest_version,
      batches: [],
      approved_at: approvedAt,
    },
  };
}

/**
 * Bind a method, a live route and an optional body to the shared tenant headers.
 *
 * @param method HTTP method
 * @param url live route, with identifiers already encoded
 * @param organizationId active organization from the session
 * @param body optional JSON body; writes receive a fresh idempotency key
 * @returns a transport descriptor the shared `send` can execute
 */
function descriptor(
  method: 'GET' | 'POST',
  url: string,
  organizationId: string,
  body?: Record<string, unknown>,
): RequestDescriptor {
  const meta = {
    activeOrganization: organizationId,
    // Every write is a command; a fresh idempotency key makes a retried start
    // or sign-off safe even when the request has no body.
    ...(method === 'POST' ? { idempotencyKey: crypto.randomUUID() } : {}),
  };
  const base = buildRequestDescriptor(method, DESCRIPTOR_BASE, meta, body);
  return { ...base, url };
}

/**
 * Normalize an unexpected rejection to the typed error the state views present.
 *
 * @param failure rejected value from the transport
 * @returns the original transport error, or a server error for any other value
 */
export function asDataExchangeError(failure: unknown): TransportError {
  if (failure instanceof TransportError) {
    return failure;
  }
  return new TransportError('server', {
    correlationId: undefined,
    code: undefined,
    retryAfterSeconds: undefined,
  });
}

/** Start a tenant export for the active organization. */
export async function startExport(organizationId: string): Promise<ExportResultResponse> {
  return send<ExportResultResponse>(descriptor('POST', EXPORT_ROUTE, organizationId));
}

/** Download the verified manifest bytes of one unexpired export job. */
export async function downloadManifest(jobId: string, organizationId: string): Promise<unknown> {
  return send<unknown>(
    descriptor('GET', `${EXPORT_ROUTE}/${encodeURIComponent(jobId)}`, organizationId),
  );
}

/** Evaluate a versioned manifest without writing any chart data. */
export async function runDryRun(
  manifest: ImportManifestRequest,
  organizationId: string,
): Promise<DryRunReportResponse> {
  return send<DryRunReportResponse>(
    descriptor('POST', `${IMPORT_ROUTE}/dry-run`, organizationId, manifest),
  );
}

/** Submit an authorized sign-off for a reviewed import checkpoint. */
export async function submitSignOff(
  request: SignOffRequest,
  organizationId: string,
): Promise<SignOffResultResponse> {
  return send<SignOffResultResponse>(
    descriptor('POST', `${IMPORT_ROUTE}/sign-off`, organizationId, request),
  );
}
