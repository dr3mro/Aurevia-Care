/**
 * Domain rules for the tenant data-exchange screens (OPS-07, OPS-08).
 *
 * <p>The module is deliberately transport-free and pure: role decisions,
 * export expiry and dry-run presentation are computed from values the caller
 * supplies, so the same rules can be exercised with synthetic data and no
 * clock, network or browser dependency.</p>
 */

import type { Role } from '../../app/navigation';

/** Roles allowed to start a tenant export (OPS-07 export permission guard). */
export const EXPORT_PERMITTED_ROLES: readonly Role[] = ['tenant_admin'];

/** Roles allowed to review a dry run and sign off a controlled import (OPS-08). */
export const IMPORT_PERMITTED_ROLES: readonly Role[] = ['tenant_admin', 'privacy_auditor'];

/** Export job metadata returned by `POST /api/v1/tenant/exports`. */
export interface ExportResultResponse {
  job_id: string;
  checksum: string;
  record_count: number;
  expires_at: string;
}

/** Counts returned inside an OPS-08 dry-run report. */
export interface DryRunCountsResponse {
  total_records: number;
  batch_count: number;
  unsupported_fields: number;
  duplicate_risk: number;
  missing_attachments: number;
  errors: number;
}

/** One declared attachment that is missing or still in quarantine. */
export interface MissingAttachmentResponse {
  record: string;
  key: string;
}

/** Deterministic, read-only dry-run report returned by the import API. */
export interface DryRunReportResponse {
  counts: DryRunCountsResponse;
  unsupported_fields: string[];
  duplicate_risk: string[];
  missing_attachments: MissingAttachmentResponse[];
  errors: string[];
}

/** Outcome of an authorized sign-off attempt. */
export interface SignOffResultResponse {
  accepted: boolean;
  reason: string;
  progress: unknown;
}

/** Presentation state of an export artifact; only these two states exist. */
export type ExportState = 'active' | 'expired';

/** One row of the export download list. */
export interface ExportListItem {
  jobId: string;
  checksum: string;
  recordCount: number;
  expiresAt: string;
  state: ExportState;
}

/**
 * Decide whether a role may request a tenant export.
 *
 * @param role active session role, never read from a form or the URL
 * @returns true only for an active tenant administrator
 */
export function canStartExport(role: Role): boolean {
  return EXPORT_PERMITTED_ROLES.includes(role);
}

/**
 * Decide whether a role may review an onboarding dry run and sign it off.
 *
 * @param role active session role, never read from a form or the URL
 * @returns true for the roles the import service authorizes
 */
export function canReviewImport(role: Role): boolean {
  return IMPORT_PERMITTED_ROLES.includes(role);
}

/**
 * Derive the disclosure state of an export artifact from its retention instant.
 *
 * <p>An unreadable expiry or clock is treated as expired. A manifest must never
 * be offered for download when the expiry cannot be proven to be in the future:
 * the safe presentation is the expired state, not an error or a guess.</p>
 *
 * @param expiresAt UTC retention instant from the export job
 * @param now UTC instant chosen by the caller (injected so display is deterministic)
 * @returns `expired` once retention has passed, otherwise `active`
 */
export function exportState(expiresAt: string, now: string): ExportState {
  const expiry = Date.parse(expiresAt);
  const current = Date.parse(now);
  if (!Number.isFinite(expiry) || !Number.isFinite(current)) {
    return 'expired';
  }
  return current >= expiry ? 'expired' : 'active';
}

/**
 * Project an export result into a download-list row.
 *
 * @param result job metadata returned by the export API
 * @param now UTC instant used to decide expiry
 * @returns an immutable row with its presentation state
 */
export function toExportListItem(result: ExportResultResponse, now: string): ExportListItem {
  return {
    jobId: result.job_id,
    checksum: result.checksum,
    recordCount: result.record_count,
    expiresAt: result.expires_at,
    state: exportState(result.expires_at, now),
  };
}

/** Display-ready, normalized OPS-08 dry-run findings. */
export interface DryRunView {
  totalRecords: number;
  batchCount: number;
  unsupportedFields: readonly string[];
  duplicateRisk: readonly string[];
  missingAttachments: readonly string[];
  errors: readonly string[];
}

/**
 * Normalize an untrusted dry-run report at the display boundary.
 *
 * <p>Missing collections default to empty so a partial response cannot crash
 * the report screen or hide the findings that are present. Attachment rows are
 * rendered as `record: key` without interpreting either value.</p>
 *
 * @param report report returned by the import API
 * @returns a view whose collections are always present
 */
export function toDryRunView(report: DryRunReportResponse): DryRunView {
  return {
    totalRecords: report.counts?.total_records ?? 0,
    batchCount: report.counts?.batch_count ?? 0,
    unsupportedFields: report.unsupported_fields ?? [],
    duplicateRisk: report.duplicate_risk ?? [],
    missingAttachments: (report.missing_attachments ?? []).map(
      (attachment) => `${attachment.record}: ${attachment.key}`,
    ),
    errors: report.errors ?? [],
  };
}

/** Machine-readable reason a sign-off was refused before it reached the API. */
export type SignOffRejection = 'missingApprover' | 'roleNotPermitted' | 'decisionNotApproved';

/**
 * Validate a sign-off locally so a named authorization cannot be submitted
 * with a blank approver, an unauthorised role or a non-affirmative decision.
 *
 * @param approver named approver from the form
 * @param role active session role
 * @param decision selected decision
 * @returns the first refusal reason, or undefined when the sign-off may be sent
 */
export function signOffRejection(
  approver: string,
  role: Role,
  decision: string,
): SignOffRejection | undefined {
  if (approver.trim() === '') {
    return 'missingApprover';
  }
  if (!IMPORT_PERMITTED_ROLES.includes(role)) {
    return 'roleNotPermitted';
  }
  if (decision !== 'approved') {
    return 'decisionNotApproved';
  }
  return undefined;
}
